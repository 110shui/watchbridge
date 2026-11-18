package com.research.watchbridge;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Bundle;
import android.widget.TextView;

import java.net.NetworkInterface;
import java.util.Collections;

public class MainActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        TextView tv = new TextView(this);
        tv.setPadding(60, 120, 60, 60);
        tv.setTextSize(16);

        // 传感器权限（BODY_SENSORS）与活动识别
        if (Build.VERSION.SDK_INT >= 29 &&
                checkSelfPermission(Manifest.permission.BODY_SENSORS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.BODY_SENSORS,
                    "android.permission.ACTIVITY_RECOGNITION"}, 1);
        }
        Intent svc = new Intent(this, BridgeService.class);
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(svc);
        else startService(svc);

        String ip = getIp();
        tv.setText("OHealth 数据桥已启动\n\n端口: 9999\n手表 IP: " + ip
                + "\n\niPhone 连接地址: " + ip + ":9999\n\n传感器数据每 3 秒推送一次 (JSON)\n"
                + "提示：手表需佩戴并保持与 iPhone 同一 WiFi");
        setContentView(tv);
    }

    String getIp() {
        try {
            for (NetworkInterface nif : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!nif.isUp() || nif.isLoopback()) continue;
                for (java.net.InetAddress addr : Collections.list(nif.getInetAddresses())) {
                    if (addr instanceof java.net.Inet4Address && !addr.isLoopbackAddress()) {
                        return addr.getHostAddress();
                    }
                }
            }
        } catch (Exception ignored) {}
        return "未知";
    }
}
