package com.research.watchbridge;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.net.NetworkInterface;
import java.util.Collections;

public class MainActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
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
        String ver = "unknown";
        try {
            ver = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Exception ignored) {}

        ScrollView scroll = new ScrollView(this);
        LinearLayout lay = new LinearLayout(this);
        lay.setOrientation(LinearLayout.VERTICAL);
        lay.setPadding(50, 80, 50, 60);
        lay.setGravity(Gravity.CENTER_HORIZONTAL);

        TextView title = new TextView(this);
        title.setText("OHealth 数据桥");
        title.setTextSize(22);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        lay.addView(title);

        TextView info = new TextView(this);
        info.setText("v" + ver + " · 已运行\n端口 9999\n手表 IP: " + ip
                + "\niPhone 连接: " + ip + ":9999");
        info.setTextSize(14);
        info.setPadding(0, 20, 0, 30);
        lay.addView(info);

        TextView remoteTitle = new TextView(this);
        remoteTitle.setText("—— 短视频遥控 ——");
        remoteTitle.setTextSize(16);
        remoteTitle.setTypeface(Typeface.DEFAULT_BOLD);
        remoteTitle.setPadding(0, 20, 0, 20);
        lay.addView(remoteTitle);

        Button next = mkButton("▶ 下一条视频（音量+）");
        next.setOnClickListener(v -> BridgeService.sendAll("{\"cmd\":\"VOLUP\"}\n"));
        lay.addView(next);

        Button prev = mkButton("◀ 上一条视频（音量-）");
        prev.setOnClickListener(v -> BridgeService.sendAll("{\"cmd\":\"VOLDOWN\"}\n"));
        lay.addView(prev);

        Button like = mkButton("❤ 点赞（音量+长按）");
        like.setOnClickListener(v -> BridgeService.sendAll("{\"cmd\":\"VOLUP2\"}\n"));
        lay.addView(like);

        TextView hint = new TextView(this);
        hint.setText("手机端抖音需开启「音量键翻页」\n（抖音→我→设置→通用设置）");
        hint.setTextSize(12);
        hint.setTextColor(Color.GRAY);
        hint.setPadding(0, 20, 0, 0);
        lay.addView(hint);

        scroll.addView(lay);
        setContentView(scroll);
    }

    Button mkButton(String text) {
        Button b = new Button(this);
        b.setText(text);
        b.setTextSize(16);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = 16;
        b.setLayoutParams(lp);
        return b;
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
