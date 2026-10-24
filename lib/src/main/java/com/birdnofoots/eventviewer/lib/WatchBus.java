package com.birdnofoots.eventviewer.lib;

import android.content.Context;
import android.content.Intent;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayDeque;
import java.util.Deque;

/**
 * 事件总线: 每个事件同时分发给
 *   1) 内存环形缓冲(GET /v1/events/*)
 *   2) 系统广播 action=com.birdnofoots.eventviewer.EVENT(运行时注册的接收器能收到)
 *   3) 已订阅的 HTTP 回调地址(POST)
 */
public class WatchBus {
    private static final String TAG = "DidiWatch";
    public static final String ACTION_EVENT = "com.birdnofoots.eventviewer.EVENT";
    private static final int MAX = 100;

    private static final Deque<JSONObject> RECENT = new ArrayDeque<>();
    private static volatile JSONObject LATEST = new JSONObject();
    private static volatile Context CTX;

    public static void init(Context c) { CTX = c.getApplicationContext(); }

    public static JSONObject latest() { return LATEST; }

    public static JSONArray tail(int n) {
        synchronized (WatchBus.class) {
            JSONArray a = new JSONArray();
            Object[] arr = RECENT.toArray();
            int from = Math.max(0, arr.length - n);
            for (int i = from; i < arr.length; i++) a.put(arr[i]);
            return a;
        }
    }

    public static void publish(Context c, JSONObject ev) {
        if (c != null && CTX == null) CTX = c.getApplicationContext();
        synchronized (WatchBus.class) {
            LATEST = ev;
            RECENT.addLast(ev);
            while (RECENT.size() > MAX) RECENT.removeFirst();
        }
        // 1) 广播(运行时注册的接收器能收到; manifest 接收器在 Android 8+ 收不到隐式广播)
        try {
            Intent i = new Intent(ACTION_EVENT);
            i.putExtra("json", ev.toString());
            i.setFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY);
            if (CTX != null) CTX.sendBroadcast(i);
        } catch (Exception e) {
            Log.w(TAG, "广播失败: " + e);
        }
        // 2) 回调
        for (String url : WatchConfig.get(CTX).callbacks()) {
            postAsync(url, ev);
        }
    }

    public static void postAsync(final String url, final JSONObject ev) {
        Thread t = new Thread(() -> post(url, ev));
        t.setDaemon(true);
        t.start();
    }

    public static void post(String url, JSONObject ev) {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setRequestMethod("POST");
            c.setConnectTimeout(2000);
            c.setReadTimeout(2000);
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            byte[] b = ev.toString().getBytes("UTF-8");
            c.setFixedLengthStreamingMode(b.length);
            try (OutputStream os = c.getOutputStream()) { os.write(b); }
            Log.i(TAG, "回调 " + url + " -> " + c.getResponseCode());
        } catch (Exception e) {
            Log.w(TAG, "回调失败 " + url + ": " + e);
        } finally {
            if (c != null) c.disconnect();
        }
    }
}
