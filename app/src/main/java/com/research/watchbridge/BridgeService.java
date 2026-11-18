package com.research.watchbridge;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
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

/**
 * 手表数据桥：SensorManager 读心率/步数 → TCP 9999 常驻 JSON 广播。
 * iPhone 与手表同 WiFi 时直连 192.168.2.93:9999，完全绕过蓝牙 OLink 认证。
 */
public class BridgeService extends Service implements SensorEventListener {

    static final int PORT = 9999;
    static final String CHANNEL = "bridge";

    SensorManager sensorManager;
    ServerSocket serverSocket;
    volatile float latestHeartRate = 0f;
    volatile float cumulativeSteps = 0f;
    volatile float lastSpo2 = 0f;
    volatile long lastHrAt = 0;

    Thread serverThread;

    @Override
    public IBinder onBind(Intent intent) { return null; }

    @Override
    public void onCreate() {
        super.onCreate();
        startForegroundNotification();
        sensorManager = (SensorManager) getSystemService(Context.SENSOR_SERVICE);
        register(Sensor.TYPE_HEART_RATE);
        register(Sensor.TYPE_STEP_COUNTER);
        // 原始 PPG/SPO2（自定义类型，注册失败无影响）
        try {
            Sensor spo2 = sensorManager.getDefaultSensor(65596);
            if (spo2 != null) sensorManager.registerListener(this, spo2, SensorManager.SENSOR_DELAY_NORMAL);
        } catch (Throwable ignored) {}
        serverThread = new Thread(this::serve, "tcp-server");
        serverThread.start();
    }

    void register(int type) {
        Sensor s = sensorManager.getDefaultSensor(type);
        if (s != null) sensorManager.registerListener(this, s, SensorManager.SENSOR_DELAY_NORMAL);
    }

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
                    .setContentText("TCP 端口 9999 · 等待 iPhone 连接")
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

    void serve() {
        android.util.Log.i(TAG, "server starting on " + PORT);
        while (!Thread.currentThread().isInterrupted()) {
            try {
                serverSocket = new ServerSocket(PORT);
                android.util.Log.i(TAG, "listening on " + PORT);
                while (true) {
                    Socket client = serverSocket.accept();
                    android.util.Log.i(TAG, "client connected: " + client.getRemoteAddress());
                    new Thread(() -> handle(client), "client").start();
                }
            } catch (IOException e) {
                android.util.Log.w(TAG, "serve error: " + e);
                sleep(2000);
            }
        }
    }

    static final String TAG = "BridgeService";

    void handle(Socket client) {
        try {
            OutputStream out = client.getOutputStream();
            SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US);
            f.setTimeZone(TimeZone.getDefault());
            while (!client.isClosed()) {
                float hr = latestHeartRate;
                long hrAge = lastHrAt == 0 ? -1 : System.currentTimeMillis() - lastHrAt;
                String json = "{\"hr\":" + (hrAge >= 0 && hrAge < 60000 ? (int) hr : 0)
                        + ",\"steps\":" + (int) cumulativeSteps
                        + ",\"spo2\":" + (int) lastSpo2
                        + ",\"hrAgeMs\":" + hrAge
                        + ",\"ts\":\"" + f.format(new Date()) + "\"}\n";
                out.write(json.getBytes());
                out.flush();
                Thread.sleep(3000);
            }
        } catch (Exception e) {
            android.util.Log.w(TAG, "handle error: " + e);
        } finally {
            try { client.close(); } catch (IOException ignored) {}
        }
    }

    static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException ignored) {}
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        if (event.sensor.getType() == Sensor.TYPE_HEART_RATE && event.values.length > 0) {
            latestHeartRate = event.values[0];
            lastHrAt = System.currentTimeMillis();
        } else if (event.sensor.getType() == Sensor.TYPE_STEP_COUNTER && event.values.length > 0) {
            cumulativeSteps = event.values[0];
        } else if (event.sensor.getType() == 65596 && event.values.length > 0) {
            lastSpo2 = event.values[0];
        }
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {}

    @Override
    public void onDestroy() {
        if (sensorManager != null) sensorManager.unregisterListener(this);
        if (serverThread != null) serverThread.interrupt();
        try { if (serverSocket != null) serverSocket.close(); } catch (IOException ignored) {}
        super.onDestroy();
    }
}
