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
import java.util.ArrayList;
import java.util.List;
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

    /**
     * 在节点树里找订单卡。
     * 判定要严格: 必须有卡片容器 order_review_fragment, 且有 价格 或 抬头 之一
     * —— 只看价格或只看容器都容易误判(比如别的界面也有价格文本)。
     */
    private JSONObject findCard(JSONArray nodes) {
        if (nodes == null || nodes.length() == 0) return null;

        boolean hasFragment = false, hasPrice = false, hasTag = false, hasLeftBtn = false;
        String price = null, tag = null;
        List<Double> journeyTop = new ArrayList<>();   // 距离行按 y 排序用
        List<String> journeyText = new ArrayList<>();
        List<double[]> addrList = new ArrayList<>();
        List<String> addrText = new ArrayList<>();

        for (int i = 0; i < nodes.length(); i++) {
            JSONObject n = nodes.optJSONObject(i);
            if (n == null) continue;
            String v = n.optString("v", "");
            String t = n.optString("t", "");
            JSONArray b = n.optJSONArray("b");
            double top = (b != null && b.length() > 1) ? b.optDouble(1, 0) : 0;
            double left = (b != null && b.length() > 0) ? b.optDouble(0, 0) : 0;

            if (v.endsWith("order_review_fragment")) hasFragment = true;
            else if (v.endsWith("price_info_card_title")) { hasPrice = true; price = t; }
            else if (v.endsWith("order_review_head_tag_view")) { hasTag = true; tag = t; }
            else if (v.endsWith("order_review_left_btn")) hasLeftBtn = true;
            else if (v.endsWith("journey_value")) { journeyTop.add(top); journeyText.add(t); }
            else if (v.endsWith("address_name")) {
                addrList.add(new double[]{top, left});
                addrText.add(t);
            }
        }

        // 严格判定: 卡片容器 + (价格 或 抬头)
        if (!hasFragment || !(hasPrice || hasTag)) return null;

        // 距离行按 y 排序, 预约单 3 行(出发时间/距您/全程), 实时单 2 行(距您/全程)
        sortTogether(journeyTop, journeyText);
        double distMe = Double.NaN, distAll = Double.NaN;
        String timeText = null;
        if (journeyText.size() >= 3) {
            timeText = journeyText.get(0);
            distMe = parseKm(journeyText.get(1));
            distAll = parseKm(journeyText.get(2));
        } else if (journeyText.size() == 2) {
            distMe = parseKm(journeyText.get(0));
            distAll = parseKm(journeyText.get(1));
        } else if (journeyText.size() == 1) {
            distAll = parseKm(journeyText.get(0));
        }

        // 起终点按 y 排序, 第一个是起点
        sortTogetherDouble(addrList, addrText);
        String from = addrText.size() > 0 ? addrText.get(0) : "";
        String to = addrText.size() > 1 ? addrText.get(1) : "";

        JSONObject o = new JSONObject();
        try {
            o.put("price", price);
            o.put("tag", tag);
            o.put("kind_name", hasLeftBtn ? "预约单" : (hasTag ? "实时单" : "未知"));
            o.put("time_text", timeText);
            if (!Double.isNaN(distMe)) o.put("dist_me", distMe);
            if (!Double.isNaN(distAll)) o.put("dist_all", distAll);
            o.put("addr_from", from);
            o.put("addr_to", to);
            o.put("has_price", hasPrice);
            o.put("has_tag", hasTag);
            o.put("has_fragment", hasFragment);
            o.put("has_left_btn", hasLeftBtn);
            o.put("node_count", nodes.length());
        } catch (Exception ignored) {}
        return o;
    }

    /** 统一折算成 km: '907m' -> 0.907, '27.5km' -> 27.5(2026-10-25 单位盲区教训) */
    private static double parseKm(String t) {
        if (t == null) return Double.NaN;
        String s = t.trim().replace(",", ".");
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("(\\d+(?:\\.\\d+)?)").matcher(s);
        if (!m.find()) return Double.NaN;
        double v;
        try { v = Double.parseDouble(m.group(1)); } catch (Exception e) { return Double.NaN; }
        if (s.endsWith("m") && !s.endsWith("km")) v = v / 1000.0;
        return v;
    }

    private static void sortTogether(List<Double> keys, List<String> vals) {
        for (int i = 1; i < keys.size(); i++) {
            double k = keys.get(i); String v = vals.get(i); int j = i - 1;
            while (j >= 0 && keys.get(j) > k) { keys.set(j+1, keys.get(j)); vals.set(j+1, vals.get(j)); j--; }
            keys.set(j+1, k); vals.set(j+1, v);
        }
    }

    private static void sortTogetherDouble(List<double[]> keys, List<String> vals) {
        for (int i = 1; i < keys.size(); i++) {
            double[] k = keys.get(i); String v = vals.get(i); int j = i - 1;
            while (j >= 0 && keys.get(j)[0] > k[0]) { keys.set(j+1, keys.get(j)); vals.set(j+1, vals.get(j)); j--; }
            keys.set(j+1, k); vals.set(j+1, v);
        }
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
