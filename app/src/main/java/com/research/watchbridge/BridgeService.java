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

import java.io.IOException;
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
    volatile long lastHrAt = 0;
    volatile boolean sampling = false;

    final AtomicInteger activeClients = new AtomicInteger(0);

    Thread serverThread, samplerThread;

    @Override
    public IBinder onBind(Intent intent) { return null; }

    @Override
    public void onCreate() {
        super.onCreate();
        startForegroundNotification();
        sensorManager = (SensorManager) getSystemService(Context.SENSOR_SERVICE);
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
        try {
            OutputStream out = client.getOutputStream();
            SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US);
            f.setTimeZone(TimeZone.getDefault());
            while (!client.isClosed()) {
                float hr = latestHeartRate;
                long hrAge = lastHrAt == 0 ? -1 : System.currentTimeMillis() - lastHrAt;
                String json = "{\"hr\":" + (hrAge >= 0 && hrAge < 120000 ? (int) hr : 0)
                        + ",\"steps\":" + (int) cumulativeSteps
                        + ",\"spo2Raw\":" + (int) lastSpo2
                        + ",\"hrAgeMs\":" + hrAge
                        + ",\"hk\":\"" + HealthKitClient.lastStatus.replace("\"", "'")
                        + "\",\"clients\":" + activeClients.get()
                        + ",\"ts\":\"" + f.format(new Date()) + "\"}\n";
                out.write(json.getBytes());
                out.flush();
                Thread.sleep(3000);
            }
        } catch (Exception e) {
            android.util.Log.w(TAG, "handle error: " + e);
        } finally {
            activeClients.decrementAndGet();
            try { client.close(); } catch (IOException ignored) {}
        }
    }

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
        }
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
