package com.research.watchbridge;

import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;

/**
 * 自实现计步器：bmi26x 加速度计无 SensorService 白名单限制（pedometer_wrist 被包名白名单拦截）。
 * 算法：加速度模值去重力 → 低通平滑 → 步态峰值检测（最小间隔 250ms，幅度自适应阈值）。
 * 常驻运行（前台服务存活期间），按天持久化。
 */
public class StepCounter implements SensorEventListener {
    private static final String TAG = "StepCounter";

    private final SensorManager sm;
    private final android.content.Context ctx;
    private boolean running = false;

    // 滤波状态
    private float lp = 0f;                       // 低通后的模值
    private boolean lpInit = false;
    private long lastStepAt = 0;
    private float avgPeak = 1.2f;                // 自适应峰值阈值（m/s²）
    private boolean aboveThreshold = false;

    private static final float LP_ALPHA = 0.2f;          // 低通系数
    private static final long MIN_STEP_INTERVAL_MS = 250; // 步频上限 ~240步/分
    private static final float MIN_PEAK = 1.0f;          // 最小步态波幅

    public StepCounter(SensorManager sm, android.content.Context ctx) {
        this.sm = sm;
        this.ctx = ctx;
    }

    public void start() {
        if (running) return;
        Sensor acc = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        if (acc == null) {
            android.util.Log.w(TAG, "no accelerometer");
            return;
        }
        sm.registerListener(this, acc, SensorManager.SENSOR_DELAY_GAME);
        running = true;
        android.util.Log.i(TAG, "step counter started (accelerometer)");
    }

    public void stop() {
        if (!running) return;
        sm.unregisterListener(this);
        running = false;
        android.util.Log.i(TAG, "step counter stopped");
    }

    /** 今日步数（跨窗口持久） */
    public int todaySteps() {
        String[] d = load();
        return parseSteps(d);
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        if (event.sensor.getType() != Sensor.TYPE_ACCELEROMETER || event.values.length < 3) return;
        long now = System.currentTimeMillis();
        float x = event.values[0], y = event.values[1], z = event.values[2];
        float mag = (float) Math.sqrt(x * x + y * y + z * z);
        // 去重力（模值 ~9.8 为静止）
        float v = Math.abs(mag - 9.81f);
        // 低通平滑
        if (!lpInit) { lp = v; lpInit = true; }
        lp = lp + LP_ALPHA * (v - lp);

        // 峰值检测：超过阈值标记上升沿，回落时计一步
        float threshold = Math.max(MIN_PEAK, avgPeak * 0.6f);
        if (lp > threshold && !aboveThreshold) {
            aboveThreshold = true;
            if (now - lastStepAt >= MIN_STEP_INTERVAL_MS) {
                lastStepAt = now;
                onStep(now);
                // 自适应阈值：指数平均峰值
                avgPeak = 0.8f * avgPeak + 0.2f * lp;
            }
        } else if (lp < threshold * 0.5f) {
            aboveThreshold = false;
        }
    }

    void onStep(long now) {
        String today = todayStr(now);
        String[] d = load();
        if (!today.equals(d[0])) { d[0] = today; d[1] = "0"; }
        d[1] = String.valueOf(Integer.parseInt(d[1]) + 1);
        save(d[0], d[1]);
    }

    String todayStr(long now) {
        return new java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(new java.util.Date(now));
    }

    String[] load() {
        android.content.SharedPreferences p = ctx.getSharedPreferences("bridge", android.content.Context.MODE_PRIVATE);
        return new String[]{p.getString("stepDate", ""), p.getString("stepCount", "0")};
    }

    void save(String date, String count) {
        android.content.SharedPreferences p = ctx.getSharedPreferences("bridge", android.content.Context.MODE_PRIVATE);
        p.edit().putString("stepDate", date).putString("stepCount", count).apply();
    }

    int parseSteps(String[] d) {
        try {
            String today = todayStr(System.currentTimeMillis());
            return today.equals(d[0]) ? Integer.parseInt(d[1]) : 0;
        } catch (Exception e) { return 0; }
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {}
}
