package com.research.watchbridge;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.ArrayList;

/**
 * 健康记录持久化存储（手表端记录 = 本桥采样计算生成的记录）。
 * JSON 格式：{"hr":[[tsMs,值x10],...],"spo2":[[tsMs,值x10],...]}
 * 内存中 hr/spo2 元素为 long[]{tsMs, valueX10}。
 */
public class RecordStore {
    static final int MAX = 600;               // 每类最多保留 600 条（覆盖 ~1-2 天）
    static final long DEDUPE_MS = 60_000;     // 60 秒内相同值去重

    final ArrayList<long[]> hr = new ArrayList<>();
    final ArrayList<long[]> spo2 = new ArrayList<>();
    final File file;

    public RecordStore(File dir) {
        file = new File(dir, "records.json");
        load();
    }

    public synchronized void appendHr(long ts, float bpm) {
        append(hr, ts, bpm);
    }

    public synchronized void appendSpo2(long ts, float pct) {
        append(spo2, ts, pct);
    }

    void append(ArrayList<long[]> list, long ts, float v) {
        long vx = Math.round(v * 10);
        if (!list.isEmpty()) {
            long[] last = list.get(list.size() - 1);
            if (ts - last[0] < DEDUPE_MS && last[1] == vx) return; // 重复值去重
        }
        list.add(new long[]{ts, vx});
        while (list.size() > MAX) list.remove(0);
    }

    public synchronized String serialize() {
        StringBuilder sb = new StringBuilder(4096);
        sb.append("{\"type\":\"records\",\"hr\":[");
        ser(sb, hr);
        sb.append("],\"spo2\":[");
        ser(sb, spo2);
        sb.append("]}\n");
        return sb.toString();
    }

    void ser(StringBuilder sb, ArrayList<long[]> list) {
        for (int i = 0; i < list.size(); i++) {
            long[] r = list.get(i);
            if (i > 0) sb.append(',');
            sb.append('[').append(r[0]).append(',').append(r[1]).append(']');
        }
    }

    void load() {
        try {
            if (!file.exists()) return;
            FileInputStream fis = new FileInputStream(file);
            byte[] buf = new byte[(int) file.length()];
            int n = fis.read(buf); fis.close();
            if (n <= 0) return;
            String s = new String(buf, 0, n);
            int hrStart = s.indexOf("\"hr\":[");
            int spo2Start = s.indexOf("\"spo2\":[");
            if (hrStart >= 0) parse(s, hrStart + 6, hr);
            if (spo2Start >= 0) parse(s, spo2Start + 9, spo2);
            android.util.Log.i("RecordStore", "loaded hr=" + hr.size() + " spo2=" + spo2.size());
        } catch (Throwable t) {
            android.util.Log.w("RecordStore", "load: " + t);
        }
    }

    void parse(String s, int from, ArrayList<long[]> out) {
        int i = from;
        int depth = 0;
        long[] cur = new long[2];
        int idx = 0;
        StringBuilder num = new StringBuilder();
        for (; i < s.length() && i < from + 40000; i++) {
            char c = s.charAt(i);
            if (c == '[') { depth++; idx = 0; num.setLength(0); continue; }
            if (c == ']') {
                depth--;
                if (idx == 1 && num.length() > 0) {
                    cur[1] = Long.parseLong(num.toString());
                    out.add(new long[]{cur[0], cur[1]});
                }
                if (depth == 0) break;
                idx = 2; // after closing inner array
                num.setLength(0);
                continue;
            }
            if (c == ',') {
                if (idx == 0 && num.length() > 0) { cur[0] = Long.parseLong(num.toString()); idx = 1; }
                num.setLength(0);
                continue;
            }
            if (Character.isDigit(c) || c == '-') num.append(c);
        }
    }

    public synchronized void save() {
        try {
            FileOutputStream fos = new FileOutputStream(file);
            fos.write(serialize().getBytes());
            fos.close();
        } catch (Throwable t) {
            android.util.Log.w("RecordStore", "save: " + t);
        }
    }
}
