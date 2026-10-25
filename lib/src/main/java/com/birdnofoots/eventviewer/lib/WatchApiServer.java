package com.birdnofoots.eventviewer.lib;

import android.content.Context;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.HashSet;
import java.util.Set;

/**
 * 本机 API 服务, 只监听 127.0.0.1:8766。
 * 同机其它 App 直接调用(不经过网络栈外部, 不需要 INTERNET 之外的权限)。
 *
 * GET  /v1/health            服务状态
 * GET  /v1/config            当前配置
 * POST /v1/config            修改配置 {pkg, act, include_tree}
 * GET  /v1/events/latest     最近一次事件
 * GET  /v1/events/tail?n=50  最近若干条
 * POST /v1/subscribe         订阅回调 {"url":"http://127.0.0.1:PORT/p"}
 * POST /v1/unsubscribe       取消订阅 {"url":"..."}
 *
 * 鉴权: 配置了 token 时必须带 ?token=xxx 或 Authorization: Bearer xxx
 */
public class WatchApiServer {
    private static final String TAG = "DidiWatch";
    private static volatile ServerSocket ss;
    private static volatile boolean starting = false;
    private static final int[] FALLBACK_PORTS = {8766, 18766, 28766, 38766, 48766};

    public static synchronized void start(final Context c) {
        if (ss != null || starting) return;   // 修: 原来 ss 在绑定成功前是 null, 并发会重复绑定
        starting = true;
        new Thread(() -> loop(c)).start();
    }

    private static void loop(Context c) {
        try {
            ServerSocket s = null;
            int port = WatchConfig.get(c).apiPort();
            for (int p : FALLBACK_PORTS) {
                try {
                    s = new ServerSocket(p, 20, InetAddress.getByName("127.0.0.1"));
                    port = p;
                    break;
                } catch (Exception e) {
                    Log.i(TAG, "端口 " + p + " 被占用, 换下一个");
                }
            }
            if (s == null) {
                Log.w(TAG, "所有候选端口都被占用, API 不可用");
                starting = false;
                return;
            }
            WatchConfig.get(c).setApiPort(port);
            ss = s;
            Log.i(TAG, "API 已启动 http://127.0.0.1:" + port + "/");
            while (true) {
                final Socket sk = s.accept();
                new Thread(() -> handle(c, sk)).start();
            }
        } catch (Exception e) {
            Log.w(TAG, "API 启动失败: " + e);
        }
    }

    private static void handle(Context c, Socket sk) {
        try (BufferedReader r = new BufferedReader(new InputStreamReader(sk.getInputStream(), "UTF-8"));
             OutputStream o = sk.getOutputStream()) {
            String line = r.readLine();
            if (line == null) return;
            String[] p = line.split(" ");
            if (p.length < 2) return;
            String method = p[0];
            String[] q1 = p[1].split("\\?", 2);
            String path = q1[0];
            String qs = q1.length > 1 ? q1[1] : "";

            int clen = 0;
            String hdr;
            while ((hdr = r.readLine()) != null && !hdr.isEmpty()) {
                if (hdr.toLowerCase().startsWith("content-length:")) {
                    clen = Integer.parseInt(hdr.substring(15).trim());
                }
            }
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < clen; i++) sb.append((char) r.read());

            String token = WatchConfig.get(c).token();
            if (!token.isEmpty() && !token.equals(param(qs, "token"))) {
                send(o, 401, "{\"error\":\"token 不对\"}");
                return;
            }
            route(c, method, path, qs, sb.toString(), o);
        } catch (Exception e) {
            Log.w(TAG, "API 处理失败: " + e);
        } finally {
            try { sk.close(); } catch (Exception ignored) {}
        }
    }

    private static void route(Context c, String method, String path, String qs, String body,
                              OutputStream o) throws Exception {
        WatchConfig cfg = WatchConfig.get(c);
        switch (method + " " + path) {
            case "GET /": {
                String docs = "DidiWatch API v1\n"
                        + "GET  /v1/health\n"
                        + "GET  /v1/config\n"
                        + "POST /v1/config  {pkg, act, include_tree}\n"
                        + "GET  /v1/events/latest\n"
                        + "GET  /v1/events/tail?n=50\n"
                        + "POST /v1/subscribe    {url}\n"
                        + "POST /v1/unsubscribe  {url}\n"
                        + "广播: action=com.birdnofoots.eventviewer.EVENT extra=json\n";
                sendText(o, 200, docs);
                return;
            }
            case "GET /v1/health": {
                JSONObject r = new JSONObject();
                r.put("ok", true);
                r.put("service_alive", WatchService.alive);
                try {
                    android.content.pm.PackageInfo pi = c.getPackageManager()
                            .getPackageInfo(c.getPackageName(), 0);
                    r.put("version", pi.versionName);
                    r.put("versionCode", pi.versionCode);
                } catch (Exception ignored) {}
                r.put("latest_ts", WatchBus.latest().optLong("ts", 0));
                r.put("pkg", cfg.pkg());
                r.put("act", cfg.act());
                send(o, 200, r.toString());
                return;
            }
            case "GET /v1/config": {
                JSONObject r = new JSONObject();
                r.put("pkg", cfg.pkg());
                r.put("act", cfg.act());
                r.put("include_tree", cfg.includeTree());
                r.put("card_detect", cfg.cardDetect());
                r.put("card_delay_ms", cfg.cardDelayMs());
                r.put("card_retries", cfg.cardRetries());
                r.put("shadow_action", cfg.shadowAction());
                r.put("action_track", cfg.actionTrack());
                r.put("callbacks", new JSONArray(cfg.callbacks()));
                r.put("api_port", cfg.apiPort());
                send(o, 200, r.toString());
                return;
            }
            case "POST /v1/config": {
                JSONObject b = new JSONObject(body);
                if (b.has("pkg")) cfg.setPkg(b.getString("pkg"));
                if (b.has("act")) cfg.setAct(b.getString("act"));
                if (b.has("include_tree")) cfg.setIncludeTree(b.getBoolean("include_tree"));
                if (b.has("card_detect")) cfg.setCardDetect(b.getBoolean("card_detect"));
                if (b.has("card_delay_ms")) cfg.setCardDelayMs(b.getInt("card_delay_ms"));
                if (b.has("card_retries")) cfg.setCardRetries(b.getInt("card_retries"));
                if (b.has("shadow_action")) cfg.setShadowAction(b.getBoolean("shadow_action"));
                if (b.has("action_track")) cfg.setActionTrack(b.getBoolean("action_track"));
                send(o, 200, "{\"ok\":true}");
                return;
            }
            case "GET /v1/rules": {
                send(o, 200, WatchConfig.get(c).rules().toString());
                return;
            }
            case "POST /v1/rules": {
                try {
                    new org.json.JSONArray(body);       // 先校验是不是合法 JSON 数组
                    WatchConfig.get(c).setRules(body);
                    send(o, 200, "{\"ok\":true}");
                } catch (Exception e) {
                    send(o, 400, "{\"error\":\"规则必须是合法 JSON 数组: " + e + "\"}");
                }
                return;
            }
            case "GET /v1/events/latest": {
                send(o, 200, WatchBus.latest().toString());
                return;
            }
            case "GET /v1/events/tail": {
                int n = 50;
                String sn = param(qs, "n");
                if (sn != null) n = Math.min(100, Integer.parseInt(sn));
                send(o, 200, WatchBus.tail(n).toString());
                return;
            }
            case "POST /v1/subscribe": {
                JSONObject b = new JSONObject(body);
                String url = b.getString("url");
                Set<String> s = cfg.callbacks();
                s.add(url);
                cfg.setCallbacks(s);
                send(o, 200, "{\"ok\":true,\"total\":" + s.size() + "}");
                return;
            }
            case "POST /v1/unsubscribe": {
                JSONObject b = new JSONObject(body);
                String url = b.getString("url");
                Set<String> s = cfg.callbacks();
                s.remove(url);
                cfg.setCallbacks(s);
                send(o, 200, "{\"ok\":true,\"total\":" + s.size() + "}");
                return;
            }
            default:
                send(o, 404, "{\"error\":\"没有这个接口: " + path + "\"}");
        }
    }

    private static String param(String qs, String key) {
        if (qs == null || qs.isEmpty()) return null;
        for (String kv : qs.split("&")) {
            String[] a = kv.split("=", 2);
            if (a.length == 2 && a[0].equals(key)) {
                try { return java.net.URLDecoder.decode(a[1], "UTF-8"); } catch (Exception e) { return a[1]; }
            }
        }
        return null;
    }

    private static void send(OutputStream o, int code, String body) throws Exception {
        byte[] b = body.getBytes("UTF-8");
        String head = "HTTP/1.1 " + code + " OK\r\n"
                + "Content-Type: application/json; charset=utf-8\r\n"
                + "Content-Length: " + b.length + "\r\n"
                + "Connection: close\r\n\r\n";
        o.write(head.getBytes("UTF-8"));
        o.write(b);
        o.flush();
    }

    private static void sendText(OutputStream o, int code, String body) throws Exception {
        byte[] b = body.getBytes("UTF-8");
        String head = "HTTP/1.1 " + code + " OK\r\n"
                + "Content-Type: text/plain; charset=utf-8\r\n"
                + "Content-Length: " + b.length + "\r\n"
                + "Connection: close\r\n\r\n";
        o.write(head.getBytes("UTF-8"));
        o.write(b);
        o.flush();
    }
}
