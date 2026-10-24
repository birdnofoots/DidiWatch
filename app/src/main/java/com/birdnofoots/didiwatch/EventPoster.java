package com.birdnofoots.didiwatch;

import android.util.Log;
import org.json.JSONObject;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * 把事件 POST 给本机的脚本服务(默认 http://127.0.0.1:8765/didi_event)。
 * 端口/地址可以在系统属性里改: setprop debug.didiwatch.url http://127.0.0.1:8765/didi_event
 */
public class EventPoster {
    private static final String TAG = "DidiWatch";
    public static final String DEFAULT_URL = "http://127.0.0.1:8765/didi_event";

    public static String url() {
        String u = System.getProperty("debug.didiwatch.url");
        return (u == null || u.isEmpty()) ? DEFAULT_URL : u;
    }

    public static void postAsync(final JSONObject payload) {
        Thread t = new Thread(() -> post(payload));
        t.setDaemon(true);
        t.start();
    }

    public static void post(JSONObject payload) {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(url()).openConnection();
            c.setRequestMethod("POST");
            c.setConnectTimeout(1500);
            c.setReadTimeout(1500);
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            byte[] body = payload.toString().getBytes("UTF-8");
            c.setFixedLengthStreamingMode(body.length);
            try (OutputStream os = c.getOutputStream()) { os.write(body); }
            int code = c.getResponseCode();
            Log.i(TAG, "POST " + url() + " -> " + code);
        } catch (Exception e) {
            Log.w(TAG, "POST 失败: " + e);
        } finally {
            if (c != null) c.disconnect();
        }
    }
}
