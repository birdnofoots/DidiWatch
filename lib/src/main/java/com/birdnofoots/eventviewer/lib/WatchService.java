package com.birdnofoots.eventviewer.lib;

import android.accessibilityservice.AccessibilityService;
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
 * 核心无障碍服务: 监听"指定应用"的界面事件。
 *
 * 两种上报:
 *   1) 窗口状态变化 -> kind=window_state_changed (总是上报)
 *   2) 卡片识别     -> kind=order_card (影子模式: 只识别不点击)
 *
 * 卡片识别为什么要有延迟:
 *   事件到达那一帧, 卡片往往还没渲染出来(实测 57 单只有 2 单在事件帧里有卡片节点)。
 *   所以事件到后先等 CARD_DELAY_MS 再取树, 没看到卡片特征就再试, 最多 CARD_RETRIES 次。
 */
public class WatchService extends AccessibilityService {
    private static final String TAG = "DidiWatch";
    private static final int MAX_NODES = 400;

    public static volatile boolean alive = false;

    private long lastHit = 0L;
    private String lastCls = "";
    private volatile long lastContentAt = 0L;      // 上一次内容变化事件的到达时刻(真实 elapsedRealtime)
    private volatile String lastContentCls = "";   // 触发的内容事件来源类名
    private volatile long lastContentLog = 0L;     // 内容事件日志限流
    private volatile long lastCardCheck = 0L;      // 卡片检查冷却(防事件洪水)
    private volatile long lastCardReport = 0L;     // 同一张卡去重
    private volatile String lastCardKey = "";

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        alive = true;
        Log.i(TAG, "无障碍服务已连接");
        WatchBus.init(this);
        WatchApiServer.start(this);
        WatchConfig cfg = WatchConfig.get(this);
        Log.i(TAG, "卡片识别=" + (cfg.cardDetect() ? "开(延迟" + cfg.cardDelayMs() + "ms×" + cfg.cardRetries() + "次)" : "关")
                + " 节点树=" + (cfg.includeTree() ? "随事件带" : "不带"));
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
    public boolean onUnbind(android.content.Intent intent) {
        alive = false;
        Log.i(TAG, "无障碍服务已解绑");
        return super.onUnbind(intent);
    }

    @Override
    public void onDestroy() {
        alive = false;
        super.onDestroy();
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null) return;
        int type = event.getEventType();
        WatchConfig cfg = WatchConfig.get(this);
        boolean watched = type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
                || type == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED;
        if (!watched) return;

        String pkg = event.getPackageName() == null ? "" : event.getPackageName().toString();
        if (!cfg.pkgs().contains(pkg)) return;   // 支持同时监听多个应用
        String cls = event.getClassName() == null ? "" : event.getClassName().toString();

        // 内容变化事件是"卡片识别"的触发器, 记录到达时刻 —— 用于分清"事件晚到"还是"取数慢"
        if (type == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) {
            lastContentAt = SystemClock.elapsedRealtime();
            lastContentCls = cls;
            if (lastContentAt - lastContentLog > 500) {        // 日志限流, 防刷屏
                lastContentLog = lastContentAt;
                Log.i(TAG, "内容变化: " + cls);
            }
        }

        // 卡片识别: 事件到后延迟取树(卡片晚于事件渲染), 有冷却防洪
        if (cfg.cardDetect() && type == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) {
            maybeCheckCard(cfg, pkg);
        }

        // 窗口状态变化照常上报
        if (type != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return;
        long now = SystemClock.elapsedRealtime();
        if (cls.equals(lastCls) && now - lastHit < 1200) return;
        lastHit = now;
        lastCls = cls;
        Log.i(TAG, "窗口变化: " + cls);

        JSONObject p = new JSONObject();
        try {
            p.put("kind", "window_state_changed");
            p.put("ts", System.currentTimeMillis());
            p.put("origin", getPackageName());
            p.put("pkg", pkg);
            p.put("cls", cls);
            p.put("etype", type);
            if (cfg.includeTree()) p.put("nodes", dumpTree());
        } catch (Exception e) {
            Log.w(TAG, "构造事件失败: " + e);
        }
        WatchBus.publish(this, p);
    }

    /** 有卡片就上报 kind=order_card; 同一张卡(同价格)去重 */
    private void maybeCheckCard(final WatchConfig cfg, final String pkg) {
        long now = SystemClock.elapsedRealtime();
        if (now - lastCardCheck < 150) return;      // 事件洪水防护
        lastCardCheck = now;
        new Thread(new Runnable() {
            @Override public void run() {
                for (int i = 0; i < cfg.cardRetries(); i++) {
                    try { Thread.sleep(cfg.cardDelayMs()); } catch (Exception e) { return; }
                    JSONArray nodes = dumpTree();
                    JSONObject card = findCard(nodes);
                    if (card == null) continue;
                    long t = SystemClock.elapsedRealtime();
                    String key = card.optString("price", "?") + "|" + card.optString("kind", "");
                    if (key.equals(lastCardKey) && t - lastCardReport < 5000) return;  // 同卡去重
                    lastCardKey = key;
                    lastCardReport = t;
                    try {
                        card.put("kind", "order_card");
                        card.put("ts", System.currentTimeMillis());
                        card.put("origin", getPackageName());
                        card.put("pkg", pkg);
                        card.put("detect_ms", (i + 1) * cfg.cardDelayMs());   // 第几次尝试取到
                        card.put("since_event_ms", SystemClock.elapsedRealtime() - lastContentAt);  // 事件到取到隔多久
                        card.put("event_cls", lastContentCls);                 // 触发这次取数的内容事件来源
                        card.put("etype", AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED);
                    } catch (Exception ignored) {}
                    WatchBus.publish(WatchService.this, card);
                    Log.i(TAG, "卡片识别 价=" + card.optString("price")
                            + " 第" + (i + 1) + "次(" + ((i + 1) * cfg.cardDelayMs()) + "ms) 取到"
                            + " 距事件" + (SystemClock.elapsedRealtime() - lastContentAt) + "ms"
                            + " 来源=" + lastContentCls);
                    return;
                }
            }
        }).start();
    }

    /** 在节点树里找订单卡特征, 找到返回卡片信息, 没有返回 null */
    private JSONObject findCard(JSONArray nodes) {
        if (nodes == null || nodes.length() == 0) return null;
        boolean hasPrice = false, hasFragment = false, hasLeftBtn = false, hasTag = false;
        String price = null, kind = null;
        for (int i = 0; i < nodes.length(); i++) {
            JSONObject n = nodes.optJSONObject(i);
            if (n == null) continue;
            String v = n.optString("v", "");
            String t = n.optString("t", "");
            if (v.endsWith("price_info_card_title")) { hasPrice = true; price = t; }
            else if (v.endsWith("order_review_fragment")) hasFragment = true;
            else if (v.endsWith("order_review_left_btn")) hasLeftBtn = true;
            else if (v.endsWith("order_review_head_tag_view")) hasTag = true;
        }
        if (!hasPrice && !hasFragment) return null;
        JSONObject o = new JSONObject();
        try {
            o.put("price", price);
            // 预约单有"不抢"按钮; 实时单没有
            kind = hasLeftBtn ? "预约单" : (hasTag ? "实时单" : "未知");
            o.put("kind_name", kind);
            o.put("has_price", hasPrice);
            o.put("has_fragment", hasFragment);
            o.put("has_left_btn", hasLeftBtn);
        } catch (Exception ignored) {}
        return o;
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
