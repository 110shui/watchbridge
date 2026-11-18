package com.research.watchbridge;

import android.content.ContentResolver;
import android.content.Context;
import android.os.Bundle;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Calendar;

import com.oplus.ocs.wearengine.bean.PermissionInfo;

/**
 * OHealthKit 客户端：通过官方 HealthKitProvider call() 协议读取真实健康数据。
 * 协议（自 HeyHealthService 逆向）：
 *   updatePermission: bundle{packageName, permissionInfo=ArrayList<PermissionInfo>}
 *   READ_RECORDS:     bundle{EXTRA_DATA=protobuf{type,start,end}} → {EXTRA_TYPE, EXTRA_DATA}
 */
public final class HealthKitClient {
    static final String TAG = "HealthKit";
    static final String AUTHORITY = "content://com.heytap.wearable.healthkit.provider";

    /** 最近一次原始响应 hex（调试用，塞进 TCP JSON） */
    public static volatile String lastReadHex = "";
    public static volatile String lastStatus = "init";

    /** 手工 protobuf：field1=type(string), field2=start(varint), field3=end(varint) */
    static byte[] readRequest(String type, long startMs, long endMs) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        writeStringField(out, 1, type);
        writeVarintField(out, 2, startMs);
        writeVarintField(out, 3, endMs);
        return out.toByteArray();
    }

    static void writeStringField(ByteArrayOutputStream out, int field, String v) {
        byte[] b = v.getBytes();
        writeVarint(out, (field << 3) | 2);
        writeVarint(out, b.length);
        out.writeBytes(b, 0, b.length);
    }

    static void writeVarintField(ByteArrayOutputStream out, int field, long v) {
        writeVarint(out, field << 3);
        writeVarint(out, v);
    }

    static void writeVarint(ByteArrayOutputStream out, long v) {
        while ((v & ~0x7F) != 0) {
            out.write((int) ((v & 0x7F) | 0x80));
            v >>>= 7;
        }
        out.write((int) v);
    }

    static String hex(byte[] b) {
        if (b == null) return "null";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < Math.min(b.length, 120); i++) sb.append(String.format("%02x", b[i]));
        return sb + (b.length > 120 ? "...(" + b.length + ")" : "");
    }

    /** 声明数据权限 + 探测版本 + 读心率/日活动，结果写日志 */
    public static void probe(Context ctx) {
        try {
            ContentResolver cr = ctx.getContentResolver();
            // 1. 声明权限
            ArrayList<PermissionInfo> perms = new ArrayList<>();
            String[] types = {"HeartRateRecord", "DailyActivityRecord", "SleepSessionRecord", "SpO2Record"};
            for (String t : types) {
                perms.add(new PermissionInfo("HEALTH", "运动健康", t, t, (byte) 1));
            }
            Bundle pb = new Bundle();
            pb.putString("packageName", "com.research.watchbridge");
            pb.putParcelableArrayList("permissionInfo", perms);
            Bundle pr = cr.call(android.net.Uri.parse(AUTHORITY), "updatePermission", null, pb);
            lastStatus = "updatePermission: " + (pr != null ? pr.toString() : "null");
            Log.i(TAG, lastStatus);

            // 2. 版本探测
            Bundle vr = cr.call(android.net.Uri.parse(AUTHORITY), "CHECK_VERSION", null, null);
            Log.i(TAG, "CHECK_VERSION: " + (vr != null ? vr.toString() : "null"));

            // 3. 读最近 24h 心率
            long end = System.currentTimeMillis();
            long start = end - 86400000L;
            Bundle rb = new Bundle();
            rb.putByteArray("EXTRA_DATA", readRequest("HeartRateRecord", start, end));
            Bundle rr = cr.call(android.net.Uri.parse(AUTHORITY), "READ_RECORDS", null, rb);
            if (rr != null) {
                byte[] data = rr.getByteArray("EXTRA_DATA");
                lastReadHex = hex(data);
                lastStatus = "READ HeartRateRecord ok, " + rr.getString("EXTRA_TYPE") + ", " + (data == null ? 0 : data.length) + "B";
            } else {
                lastStatus = "READ_RECORDS null response";
            }
            Log.i(TAG, lastStatus + " hex=" + lastReadHex);
        } catch (Throwable t) {
            lastStatus = "probe error: " + t;
            Log.w(TAG, lastStatus);
        }
    }
}
