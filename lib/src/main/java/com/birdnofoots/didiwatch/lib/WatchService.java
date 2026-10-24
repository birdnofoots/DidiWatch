package com.birdnofoots.didiwatch.lib;

import android.accessibilityservice.AccessibilityService;
import android.content.Context;
import android.graphics.Rect;
import android.os.SystemClock;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * 核心无障碍服务: 监听"指定应用的指定界面"出现, 一出现就把事件丢给 WatchBus。
 * 过滤目标可由宿主 App / API 动态配置(WatchConfig), 默认滴滴订单卡界面。
 */
public class WatchService extends AccessibilityService {
    private static final String TAG = "DidiWatch";
    private static final long DEDUP_MS = 1200;
    private static final int MAX_NODES = 400;

    public static volatile boolean alive = false;

    private long lastHit = 0L;
    private String lastCls = "";

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        alive = true;
        Log.i(TAG, "无障碍服务已连接");
        WatchBus.init(this);
        WatchApiServer.start(this);
        JSONObject p = new JSONObject();
        try {
            p.put("kind", "service_connected");
            p.put("ts", System.currentTimeMillis());
            p.put("origin", getPackageName());
        } catch (Exception ignored) {}
        WatchBus.publish(this, p);
    }

    @Override
    public void onInterrupt() {}

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null) return;
        if (event.getEventType() != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return;
        WatchConfig cfg = WatchConfig.get(this);

        String pkg = event.getPackageName() == null ? "" : event.getPackageName().toString();
        if (!cfg.pkg().equals(pkg)) return;
        String cls = event.getClassName() == null ? "" : event.getClassName().toString();
        if (!cfg.act().isEmpty() && !cls.contains(cfg.act())) return;

        long now = SystemClock.elapsedRealtime();
        if (cls.equals(lastCls) && now - lastHit < DEDUP_MS) return;
        lastHit = now;
        lastCls = cls;
        Log.i(TAG, "命中界面: " + cls);

        JSONObject p = new JSONObject();
        try {
            p.put("kind", "window_state_changed");
            p.put("ts", System.currentTimeMillis());
            p.put("origin", getPackageName());
            p.put("pkg", pkg);
            p.put("cls", cls);
            p.put("etype", event.getEventType());
            if (cfg.includeTree()) p.put("nodes", dumpTree());
        } catch (Exception e) {
            Log.w(TAG, "构造事件失败: " + e);
        }
        WatchBus.publish(this, p);
    }

    private JSONArray dumpTree() {
        JSONArray out = new JSONArray();
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return out;
        Deque<AccessibilityNodeInfo> q = new ArrayDeque<>();
        q.add(root);
        int n = 0;
        while (!q.isEmpty() && n < MAX_NODES) {
            AccessibilityNodeInfo node = q.poll();
            if (node == null) continue;
            try {
                Rect rc = new Rect();
                node.getBoundsInScreen(rc);
                JSONObject o = new JSONObject();
                o.put("c", node.getClassName() == null ? null : node.getClassName().toString());
                o.put("v", node.getViewIdResourceName());
                o.put("t", node.getText());
                o.put("d", node.getContentDescription());
                o.put("b", new JSONArray().put(rc.left).put(rc.top).put(rc.right).put(rc.bottom));
                o.put("k", node.isClickable());
                o.put("e", node.isEnabled());
                out.put(o);
                n++;
                for (int i = 0; i < node.getChildCount(); i++) {
                    AccessibilityNodeInfo ch = node.getChild(i);
                    if (ch != null) q.add(ch);
                }
            } catch (Exception ignored) {}
        }
        return out;
    }
}
