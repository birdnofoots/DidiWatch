package com.birdnofoots.didiwatch;

import android.accessibilityservice.AccessibilityService;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.SystemClock;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * 核心: 监听滴滴车主订单卡界面(BroadOrderActivity)的无障碍窗口切换事件,
 * 一出现就立刻通知本机脚本 —— 用来替代 "adb dumpsys 轮询发现来单", 实现零 adb。
 *
 * 只上报界面切换事件, 不读取任何业务数据(树是可选的, 默认不带)。
 */
public class DidiWatchService extends AccessibilityService {
    private static final String TAG = "DidiWatch";
    public static final String TARGET_PKG = "com.sdu.didi.gsui";
    public static final String TARGET_ACT = "BroadOrderActivity";
    private static final String PREFS = "didiwatch";
    private static final long DEDUP_MS = 1500;
    private static final int MAX_NODES = 400;

    public static volatile boolean alive = false;
    public static volatile long lastEventAt = 0L;
    public static volatile String lastInfo = "尚无事件";

    private long lastHit = 0L;
    private String lastCls = "";

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        alive = true;
        Log.i(TAG, "无障碍服务已连接");
        JSONObject p = new JSONObject();
        try {
            p.put("kind", "service_connected");
            p.put("ts", System.currentTimeMillis());
            p.put("origin", "apk");
        } catch (Exception ignored) {}
        EventPoster.postAsync(p);
    }

    @Override
    public void onInterrupt() {}

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null) return;
        if (event.getEventType() != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return;

        String pkg = event.getPackageName() == null ? "" : event.getPackageName().toString();
        if (!TARGET_PKG.equals(pkg)) return;
        String cls = event.getClassName() == null ? "" : event.getClassName().toString();
        if (!cls.contains(TARGET_ACT)) return;

        long now = SystemClock.elapsedRealtime();
        if (cls.equals(lastCls) && now - lastHit < DEDUP_MS) return;   // 去抖
        lastHit = now;
        lastCls = cls;
        lastEventAt = System.currentTimeMillis();
        lastInfo = cls;
        Log.i(TAG, "订单卡界面: " + cls);

        JSONObject p = new JSONObject();
        try {
            p.put("kind", "order_card");
            p.put("ts", System.currentTimeMillis());
            p.put("origin", "apk");
            p.put("pkg", pkg);
            p.put("cls", cls);
            p.put("etype", event.getEventType());
            if (includeTree()) {
                p.put("nodes", dumpTree());
            }
        } catch (Exception e) {
            Log.w(TAG, "构造事件失败: " + e);
        }
        EventPoster.postAsync(p);
        saveLast(cls);
    }

    private boolean includeTree() {
        try {
            SharedPreferences sp = getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            return sp.getBoolean("include_tree", false);
        } catch (Exception e) {
            return false;
        }
    }

    private void saveLast(String cls) {
        try {
            getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                    .putString("last_cls", cls)
                    .putLong("last_at", System.currentTimeMillis())
                    .apply();
        } catch (Exception ignored) {}
    }

    /** 把当前窗口的节点树压成精简数组(可选, 默认关) */
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
                JSONObject o = new JSONObject();
                o.put("c", node.getClassName() == null ? null : node.getClassName().toString());
                o.put("v", node.getViewIdResourceName());
                o.put("t", node.getText());
                o.put("d", node.getContentDescription());
                android.graphics.Rect rc = new android.graphics.Rect();
                node.getBoundsInScreen(rc);
                o.put("b", new JSONArray()
                        .put(rc.left)
                        .put(rc.top)
                        .put(rc.right)
                        .put(rc.bottom));
                o.put("k", node.isClickable());
                o.put("e", node.isEnabled());
                out.put(o);
                n++;
                for (int i = 0; i < node.getChildCount(); i++) {
                    AccessibilityNodeInfo ch = node.getChild(i);
                    if (ch != null) q.add(ch);
                }
            } catch (Exception ignored) {
            }
        }
        return out;
    }
}
