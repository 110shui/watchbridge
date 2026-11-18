package com.research.watchbridge;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.IBinder;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 手表数据桥：按需窗口采样传感器 → TCP 9999 JSON 推送。
 *
 * 功耗策略（不常开 PPG）：
 *  - 有客户端连接：每 30 秒开一个 15 秒采样窗口
 *  - 无客户端：每 5 分钟开一个 20 秒采样窗口
 *  - 每个窗口开始时才 registerListener（权限授予后下一个窗口自动生效），窗口结束立即 unregister
 *  - 步数计硬件计数器，窗口内读一次
 */
public class BridgeService extends Service implements SensorEventListener {

    static final int PORT = 9999;
    static final String CHANNEL = "bridge";
    static final String TAG = "BridgeService";

    // 窗口参数
    static final long WINDOW_ACTIVE_MS = 15000;      // 有客户端：采样 15 秒
    static final long GAP_ACTIVE_MS = 15000;         // 间隔 15 秒（周期 30 秒）
    static final long WINDOW_IDLE_MS = 20000;        // 无客户端：采样 20 秒
    static final long GAP_IDLE_MS = 280000;          // 间隔 ~5 分钟

    SensorManager sensorManager;
    ServerSocket serverSocket;
    volatile float latestHeartRate = 0f;
    volatile float cumulativeSteps = 0f;
    volatile float lastSpo2 = 0f;
    volatile float lastSpo2Pct = 0f;      // 合成血氧百分比（0=无有效值）
    volatile long lastHrAt = 0;
    volatile boolean sampling = false;
    volatile boolean worn = false;

    // 记录存储（手表端健康记录）
    RecordStore recordStore;
    static volatile long hrIntervalSec = 30;    // 心率自动测定间隔
    static volatile long spo2IntervalSec = 60;  // 血氧自动测定间隔

    // 所有客户端输出流（手表→手机命令广播用）
    static final java.util.concurrent.CopyOnWriteArrayList<OutputStream> clientOuts =
            new java.util.concurrent.CopyOnWriteArrayList<>();

    // SpO2 原始双通道缓冲（65596），用于合成血氧百分比
    final java.util.ArrayDeque<float[]> spo2Buf = new java.util.ArrayDeque<>(); // [tsSec, ch0, ch1]
    static final int SPO2_BUF_MAX = 128;

    // PPG 原始波形缓冲（max86178_hr 65597），用于自相关心率估计
    static final int PPG_BUF_MAX = 128;              // ~25 秒 @5Hz
    final java.util.ArrayDeque<float[]> ppgBuf = new java.util.ArrayDeque<>(); // [tsSec, value]
    final Object ppgLock = new Object();

    final AtomicInteger activeClients = new AtomicInteger(0);

    Thread serverThread, samplerThread;

    @Override
    public IBinder onBind(Intent intent) { return null; }

    @Override
    public void onCreate() {
        super.onCreate();
        startForegroundNotification();
        sensorManager = (SensorManager) getSystemService(Context.SENSOR_SERVICE);
        recordStore = new RecordStore(getFilesDir());
        try {
            android.content.SharedPreferences p = getSharedPreferences("bridge", MODE_PRIVATE);
            hrIntervalSec = Math.max(15, p.getLong("hrInterval", 30));
            spo2IntervalSec = Math.max(15, p.getLong("spo2Interval", 60));
        } catch (Throwable ignored) {}
        serverThread = new Thread(this::serve, "tcp-server");
        serverThread.start();
        samplerThread = new Thread(this::sampler, "sampler");
        samplerThread.start();
    }

    // ---------------- 采样调度 ----------------

    void sampler() {
        while (!Thread.currentThread().isInterrupted()) {
            boolean online = activeClients.get() > 0;
            long window = online ? WINDOW_ACTIVE_MS : WINDOW_IDLE_MS;

            startSampling();
            sleep(window);
            computeHrFromPpg();   // 窗口结束前算一次，无客户端也可记录
            computeSpo2();
            recordMeasurements(false); // 按测定间隔落库（自动测定）
            stopSampling();

            // 等待期：每秒检查客户端上线，一旦上线立即缩短等待（1 秒内开下一个窗口）
            long gapStart = System.currentTimeMillis();
            long maxGap = online ? GAP_ACTIVE_MS : GAP_IDLE_MS;
            while (System.currentTimeMillis() - gapStart < maxGap) {
                boolean nowOnline = activeClients.get() > 0;
                if (nowOnline != online) break;   // 连接状态变化 → 立即进入下一轮
                sleep(1000);
            }
        }
    }

    void startSampling() {
        sampling = true;
        // 每个窗口重新注册（权限授予后自动生效）
        try {
            register(Sensor.TYPE_HEART_RATE);
            register(Sensor.TYPE_STEP_COUNTER);
            register(34); // 佩戴检测
            // 优先注册厂商芯片级心率 max86178_hr (65597)——不受 SensorService 白名单限制
            boolean hrSensorFound = false;
            for (Sensor s : sensorManager.getSensorList(-1)) {
                if (s.getType() == 65597) {
                    sensorManager.registerListener(this, s, SensorManager.SENSOR_DELAY_NORMAL);
                    hrSensorFound = true;
                    android.util.Log.i(TAG, "registered " + s.getName() + " vendor=" + s.getVendor());
                }
            }
            android.util.Log.i(TAG, "max86178_hr found=" + hrSensorFound);
            Sensor spo2 = sensorManager.getDefaultSensor(65596);
            if (spo2 != null) sensorManager.registerListener(this, spo2, SensorManager.SENSOR_DELAY_NORMAL);
        } catch (Throwable t) {
            android.util.Log.w(TAG, "startSampling: " + t);
        }
        android.util.Log.i(TAG, "sampling window open (clients=" + activeClients.get() + ")");
        HealthKitClient.probe(this);
    }

    void stopSampling() {
        sampling = false;
        try { sensorManager.unregisterListener(this); } catch (Throwable ignored) {}
        synchronized (ppgLock) { ppgBuf.clear(); spo2Buf.clear(); }
        android.util.Log.i(TAG, "sampling window closed");
    }

    boolean hasBodySensorPermission() {
        return checkSelfPermission(android.Manifest.permission.BODY_SENSORS)
                == PackageManager.PERMISSION_GRANTED;
    }

    void register(int type) {
        Sensor s = sensorManager.getDefaultSensor(type);
        if (s != null) sensorManager.registerListener(this, s, SensorManager.SENSOR_DELAY_NORMAL);
    }

    // ---------------- 前台通知 ----------------

    void startForegroundNotification() {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (android.os.Build.VERSION.SDK_INT >= 26) {
            NotificationChannel ch = new NotificationChannel(CHANNEL, "Watch Bridge", NotificationManager.IMPORTANCE_LOW);
            nm.createNotificationChannel(ch);
        }
        Notification n;
        if (android.os.Build.VERSION.SDK_INT >= 26) {
            n = new Notification.Builder(this, CHANNEL)
                    .setContentTitle("OHealth 数据桥运行中")
                    .setContentText("TCP 9999 · 按需采样模式")
                    .setSmallIcon(android.R.drawable.stat_notify_sync)
                    .build();
        } else {
            n = new Notification.Builder(this)
                    .setContentTitle("OHealth 数据桥运行中")
                    .setSmallIcon(android.R.drawable.stat_notify_sync)
                    .build();
        }
        startForeground(1, n);
    }

    // ---------------- TCP 服务 ----------------

    void serve() {
        android.util.Log.i(TAG, "server starting on " + PORT);
        while (!Thread.currentThread().isInterrupted()) {
            try {
                serverSocket = new ServerSocket(PORT);
                android.util.Log.i(TAG, "listening on " + PORT);
                while (true) {
                    Socket client = serverSocket.accept();
                    android.util.Log.i(TAG, "client connected: " + client.getRemoteSocketAddress());
                    activeClients.incrementAndGet();
                    new Thread(() -> handle(client), "client").start();
                }
            } catch (IOException e) {
                android.util.Log.w(TAG, "serve error: " + e);
                sleep(2000);
            }
        }
    }

    void handle(Socket client) {
        OutputStream out = null;
        try {
            out = client.getOutputStream();
            clientOuts.add(out);
            BufferedReader in = new BufferedReader(new InputStreamReader(client.getInputStream()));
            // 读命令线程（手机→手表：RECORDS/MEASURE/CONFIG）
            Thread reader = new Thread(() -> readLoop(client, in, out), "reader-" + client.getPort());
            reader.start();
            // 写数据循环（每 3 秒推送状态）
            SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US);
            f.setTimeZone(TimeZone.getDefault());
            while (!client.isClosed()) {
                computeHrFromPpg();   // PPG 自相关估计心率
                float hr = latestHeartRate;
                long hrAge = lastHrAt == 0 ? -1 : System.currentTimeMillis() - lastHrAt;
                String json = "{\"hr\":" + (hrAge >= 0 && hrAge < 120000 ? (int) hr : 0)
                        + ",\"steps\":" + (int) cumulativeSteps
                        + ",\"spo2Raw\":" + (int) lastSpo2
                        + ",\"spo2\":" + (int) lastSpo2Pct
                        + ",\"worn\":" + (worn ? 1 : 0)
                        + ",\"hrAgeMs\":" + hrAge
                        + ",\"hk\":\"" + HealthKitClient.lastStatus.replace("\"", "'")
                        + "\",\"clients\":" + activeClients.get()
                        + ",\"ts\":\"" + f.format(new Date()) + "\"}\n";
                synchronized (out) {
                    out.write(json.getBytes());
                    out.flush();
                }
                Thread.sleep(3000);
            }
        } catch (Exception e) {
            android.util.Log.w(TAG, "handle error: " + e);
        } finally {
            activeClients.decrementAndGet();
            if (out != null) clientOuts.remove(out);
            try { client.close(); } catch (IOException ignored) {}
        }
    }

    void readLoop(Socket client, BufferedReader in, OutputStream out) {
        try {
            String line;
            while (!client.isClosed() && (line = in.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) continue;
                android.util.Log.i(TAG, "cmd: " + line);
                if (line.startsWith("RECORDS")) {
                    byte[] data = recordStore.serialize().getBytes();
                    synchronized (out) { out.write(data); out.flush(); }
                } else if (line.startsWith("MEASURE")) {
                    Object[] r = doMeasure();
                    long now = System.currentTimeMillis();
                    float hr = (Float) r[0];
                    float spo2 = (Float) r[1];
                    if (hr > 0) recordStore.appendHr(now, hr);
                    if (spo2 > 0) recordStore.appendSpo2(now, spo2);
                    recordStore.save();
                    String resp = "{\"type\":\"measure\",\"hr\":" + (int) hr
                            + ",\"spo2\":" + (int) spo2
                            + ",\"worn\":" + (worn ? 1 : 0)
                            + ",\"ts\":" + now + "}\n";
                    synchronized (out) { out.write(resp.getBytes()); out.flush(); }
                } else if (line.startsWith("CONFIG")) {
                    applyConfig(line);
                    String resp = "{\"type\":\"config\",\"hrInterval\":" + hrIntervalSec
                            + ",\"spo2Interval\":" + spo2IntervalSec + "}\n";
                    synchronized (out) { out.write(resp.getBytes()); out.flush(); }
                }
            }
        } catch (Exception e) {
            android.util.Log.w(TAG, "readLoop error: " + e);
        }
    }

    void applyConfig(String line) {
        try {
            android.content.SharedPreferences p = getSharedPreferences("bridge", MODE_PRIVATE);
            android.content.SharedPreferences.Editor e = p.edit();
            for (String tok : line.split("\\s+")) {
                if (tok.startsWith("hrInterval=")) {
                    hrIntervalSec = Math.max(15, Long.parseLong(tok.substring(11)));
                    e.putLong("hrInterval", hrIntervalSec);
                } else if (tok.startsWith("spo2Interval=")) {
                    spo2IntervalSec = Math.max(15, Long.parseLong(tok.substring(13)));
                    e.putLong("spo2Interval", spo2IntervalSec);
                }
            }
            e.apply();
            android.util.Log.i(TAG, "config hrInterval=" + hrIntervalSec + " spo2Interval=" + spo2IntervalSec);
        } catch (Throwable t) {
            android.util.Log.w(TAG, "applyConfig: " + t);
        }
    }

    /** 手动测量（立即采样 10 秒并返回结果） */
    Object[] doMeasure() {
        if (!sampling) {
            startSampling();
            sleep(10000);
            computeHrFromPpg();
            computeSpo2();
            stopSampling();
        } else {
            computeHrFromPpg();
            computeSpo2();
        }
        float hr = lastHrAt > 0 && System.currentTimeMillis() - lastHrAt < 60000 ? latestHeartRate : 0f;
        return new Object[]{hr, lastSpo2Pct};
    }

    /** 手表→手机命令广播（短视频遥控等） */
    public static void sendAll(String cmd) {
        for (OutputStream out : clientOuts) {
            try {
                synchronized (out) { out.write(cmd.getBytes()); out.flush(); }
            } catch (Throwable ignored) {}
        }
    }

    /** 采样窗口结束落库（force=true 手动测量立即记录） */
    void recordMeasurements(boolean force) {
        try {
            long now = System.currentTimeMillis();
            if (!worn && !force) return;
            if (lastHrAt > 0 && now - lastHrAt < 60000) {
                if (force || now - lastHrRecordAt >= hrIntervalSec * 1000) {
                    recordStore.appendHr(now, latestHeartRate);
                    lastHrRecordAt = now;
                }
            }
            if (lastSpo2Pct > 0) {
                if (force || now - lastSpo2RecordAt >= spo2IntervalSec * 1000) {
                    recordStore.appendSpo2(now, lastSpo2Pct);
                    lastSpo2RecordAt = now;
                }
            }
            recordStore.save();
        } catch (Throwable t) {
            android.util.Log.w(TAG, "recordMeasurements: " + t);
        }
    }
    volatile long lastHrRecordAt = 0;
    volatile long lastSpo2RecordAt = 0;

    static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException ignored) {}
    }

    // ---------------- 传感器回调 ----------------

    @Override
    public void onSensorChanged(SensorEvent event) {
        int type = event.sensor.getType();
        if (event.values.length == 0) return;
        // 仅有效值(>0)才刷新时间戳，避免 0.0 心率造成"假新鲜"数据
        if (type == Sensor.TYPE_HEART_RATE || type == 65597 /* max86178_hr */) {
            float v = event.values[0];
            if (v > 0) {
                latestHeartRate = v;
                lastHrAt = System.currentTimeMillis();
                android.util.Log.i(TAG, "hr(type" + type + ")=" + latestHeartRate);
            }
        } else if (type == Sensor.TYPE_STEP_COUNTER && event.values[0] > 0) {
            cumulativeSteps = event.values[0];
        } else if (type == 65596) {
            lastSpo2 = event.values[0];
            if (event.values.length >= 2) {
                synchronized (ppgLock) {
                    float ts = event.timestamp / 1000000000f;
                    spo2Buf.addLast(new float[]{ts, event.values[0], event.values[1]});
                    while (spo2Buf.size() > SPO2_BUF_MAX) spo2Buf.removeFirst();
                }
            }
        } else if (type == 34 /* LOW_LATENCY_OFFBODY_DETECT */) {
            worn = event.values[0] > 0.5f;
            android.util.Log.i(TAG, "offbody worn=" + worn);
        }
        // PPG 波形入缓冲（max86178_hr），去重：80ms 内的重复事件丢弃
        if (type == 65597 && event.values.length > 0) {
            synchronized (ppgLock) {
                float ts = event.timestamp / 1000000000f;
                float[] last = ppgBuf.isEmpty() ? null : ppgBuf.peekLast();
                if (last != null && ts - last[0] < 0.08f && event.values[0] == last[1]) return;
                if (last != null && ts - last[0] < 0.02f) return;
                ppgBuf.addLast(new float[]{ts, event.values[0]});
                while (ppgBuf.size() > PPG_BUF_MAX) ppgBuf.removeFirst();
            }
        }
    }

    // ---------------- PPG 自相关心率估计 ----------------
    // 5Hz 采样：去趋势 + 自相关求周期，抛物线插值细化，输出 40-180 bpm
    void computeHrFromPpg() {
        float[][] d;
        synchronized (ppgLock) {
            if (ppgBuf.size() < 40) return;          // <8 秒数据不计算
            d = ppgBuf.toArray(new float[0][]);
        }
        int n = d.length;
        // 只取最近 12 秒
        float tEnd = d[n - 1][0];
        int start = 0;
        while (start < n - 1 && tEnd - d[start][0] > 12f) start++;
        int m = n - start;
        if (m < 40) return;
        float[] x = new float[m];
        float mean = 0;
        for (int i = 0; i < m; i++) { x[i] = d[start + i][1]; mean += x[i]; }
        mean /= m;
        // 去趋势：滑动均值（约 3 秒 = 15 点）
        float[] x2 = new float[m];
        for (int i = 0; i < m; i++) {
            int a = Math.max(0, i - 7), b = Math.min(m - 1, i + 7);
            float lm = 0;
            for (int j = a; j <= b; j++) lm += x[j];
            x2[i] = x[i] - lm / (b - a + 1);
        }
        // 归一化能量
        float energy = 0;
        for (int i = 0; i < m; i++) energy += x2[i] * x2[i];
        if (energy < 1e-6f) return;
        // 自相关：lag 范围按实际采样间隔动态计算（心率 40-180bpm）
        float[] dt = new float[m];
        for (int i = 1; i < m; i++) dt[i] = d[start + i][0] - d[start][0];
        float step = dt[m - 1] / (m - 1);
        if (step <= 0) return;
        int lagMin = Math.max(2, (int) (0.33f / step));
        int lagMax = Math.min(60, (int) (1.5f / step) + 1);
        if (lagMax - lagMin < 2) return;
        float bestR = -Float.MAX_VALUE; int bestLag = -1;
        float[] rs = new float[62];
        for (int lag = lagMin; lag <= lagMax; lag++) {
            float r = 0; int cnt = 0;
            for (int i = 0; i + lag < m; i++) { r += x2[i] * x2[i + lag]; cnt++; }
            rs[lag] = r / Math.max(1, cnt);
            if (rs[lag] > bestR) { bestR = rs[lag]; bestLag = lag; }
        }
        if (bestLag < 0) return;
        // 谐波跳过：r[2*lag] 接近 r[lag] 时优先低频（防止 75bpm 被估成 150bpm）
        while (bestLag * 2 <= lagMax && rs[bestLag * 2] >= 0.85f * bestR) {
            bestLag *= 2;
            bestR = rs[bestLag];
        }
        // 信号质量：归一化自相关系数过低说明噪声主导（未贴紧/无脉搏）
        float rNorm = bestR / (energy / m);
        if (rNorm < 0.25f) { android.util.Log.i(TAG, "ppg quality low: " + rNorm); return; }
        // 能量太低说明未佩戴/信号无效
        float amp = (float) Math.sqrt(energy / m);
        if (amp < 500) { android.util.Log.i(TAG, "ppg amp too low: " + amp); return; }
        // 抛物线插值细化峰位（delta 约束在 ±0.5 内，防止飞出 lag 邻域）
        float lagF = bestLag;
        if (bestLag > lagMin && bestLag < lagMax) {
            float y0 = rs[bestLag - 1], y1 = rs[bestLag], y2 = rs[bestLag + 1];
            float denom = (y0 - 2 * y1 + y2);
            if (Math.abs(denom) > 1e-9f) {
                float delta = 0.5f * (y0 - y2) / denom;
                if (delta > 0.5f) delta = 0.5f;
                if (delta < -0.5f) delta = -0.5f;
                lagF = bestLag + delta;
            }
        }
        float periodSec = lagF * step;
        float bpm = 60f / periodSec;
        if (bpm >= 40 && bpm <= 180) {
            latestHeartRate = bpm;
            lastHrAt = System.currentTimeMillis();
            android.util.Log.i(TAG, "ppg-hr=" + (int) bpm + " amp=" + (int) amp + " step=" + step);
        } else {
            android.util.Log.i(TAG, "ppg-hr out of range: " + bpm);
        }
    }

    // ---------------- PPG 合成血氧估计 ----------------
    // max86178_spo2 双通道（红/红外原始值）：R=(AC1/DC1)/(AC2/DC2)，SpO2≈110-25R
    // 通道角色未知 → 两个方向都算，取落在 85-100 的候选；结果限幅 [88,100]
    void computeSpo2() {
        float[][] d;
        synchronized (ppgLock) {
            if (spo2Buf.size() < 30) return;      // <6 秒数据不算
            d = spo2Buf.toArray(new float[0][]);
        }
        int m = d.length;
        float[] c0 = new float[m], c1 = new float[m];
        float dc0 = 0, dc1 = 0;
        for (int i = 0; i < m; i++) { c0[i] = d[i][1]; c1[i] = d[i][2]; dc0 += c0[i]; dc1 += c1[i]; }
        dc0 /= m; dc1 /= m;
        if (dc0 < 1 || dc1 < 1) return;
        // AC = 去趋势（3 秒滑动均值）后的 RMS
        float ac0 = rmsDetrended(c0), ac1 = rmsDetrended(c1);
        if (ac0 <= 0 || ac1 <= 0) return;
        float r01 = (ac0 / dc0) / (ac1 / dc1);
        float r10 = (ac1 / dc1) / (ac0 / dc0);
        float best = 0;
        float s01 = 110f - 25f * r01;
        float s10 = 110f - 25f * r10;
        if (s01 >= 85 && s01 <= 100) best = s01;
        if (best == 0 && s10 >= 85 && s10 <= 100) best = s10;
        if (best == 0) { android.util.Log.i(TAG, "spo2 candidate out of range: " + s01 + "/" + s10); return; }
        if (best < 88) best = 88;
        if (best > 100) best = 100;
        // 平滑：与上次值差异过大时取均值过渡
        if (lastSpo2Pct > 0 && Math.abs(best - lastSpo2Pct) > 5) best = (best + lastSpo2Pct) / 2;
        lastSpo2Pct = best;
        android.util.Log.i(TAG, "ppg-spo2=" + (int) best + " ac0=" + (int) ac0 + " ac1=" + (int) ac1);
    }

    float rmsDetrended(float[] x) {
        int m = x.length;
        float energy = 0;
        for (int i = 0; i < m; i++) {
            int a = Math.max(0, i - 7), b = Math.min(m - 1, i + 7);
            float lm = 0;
            for (int j = a; j <= b; j++) lm += x[j];
            float v = x[i] - lm / (b - a + 1);
            energy += v * v;
        }
        return (float) Math.sqrt(energy / m);
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {}

    @Override
    public void onDestroy() {
        if (serverThread != null) serverThread.interrupt();
        if (samplerThread != null) samplerThread.interrupt();
        try { sensorManager.unregisterListener(this); } catch (Throwable ignored) {}
        try { if (serverSocket != null) serverSocket.close(); } catch (IOException ignored) {}
        super.onDestroy();
    }
}
