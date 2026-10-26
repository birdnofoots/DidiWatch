package com.birdnofoots.eventviewer.lib;

import android.accessibilityservice.AccessibilityService;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.hardware.HardwareBuffer;
import android.os.SystemClock;
import android.util.Log;
import android.view.Display;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.accessibilityservice.ScreenshotResult;

import java.io.ByteArrayOutputStream;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayDeque;
import java.util.concurrent.atomic.AtomicBoolean;
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
    private final AtomicBoolean tracking = new AtomicBoolean(false);   // 卡片按钮跟踪中(同一时间只跟一张)
    private final Object trackLock = new Object();                     // 事件唤醒跟踪线程用
    private volatile long lastTrackCheck = 0L;                        // 上次复查时刻(限流)

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
        // ★窗口状态变化也要触发 —— 卡片是 MainActivity 上的覆盖层, 有些卡只发窗口事件
        //   不发内容变化事件(实测 09:04 那张只报了 window_state_changed, APK 漏检了)
        if (cfg.cardDetect() && (type == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
                || type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED)) {
            maybeCheckCard(cfg, pkg, cls);
        }

        // 按钮跟踪: 内容变化事件立刻唤醒复查(抢单按钮从"3秒后开始抢单"变"抢预约单"
        // 就是一次内容变化, 事件驱动才能做到和 GKD 一样即时)
        if (type == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED && tracking.get()) {
            synchronized (trackLock) { trackLock.notifyAll(); }
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
    private void maybeCheckCard(final WatchConfig cfg, final String pkg, final String cls) {
        long now = SystemClock.elapsedRealtime();
        if (now - lastCardCheck < 150) return;      // 事件洪水防护
        lastCardCheck = now;
        new Thread(new Runnable() {
            @Override public void run() {
                String lastPrice = null;      // ★价格稳定判定(本线程内连续两次读到同价才算稳定)
                for (int i = 0; i < cfg.cardRetries(); i++) {
                    try { Thread.sleep(cfg.cardDelayMs()); } catch (Exception e) { return; }
                    JSONArray nodes = dumpTree();
                    // ★两套逻辑并跑: 写死的 findCard(legacy) + 可配置 RuleEngine(rule), 用数据验证等价性
                    JSONObject card = findCard(nodes);                       // 写死
                    JSONObject ruleHit = RuleEngine.evaluate(nodes, pkg, cls, cfg.rules());   // 规则
                    if (card == null && ruleHit == null) continue;
                    JSONObject _cmp = new JSONObject();   // 对比结果(写死 vs 规则)
                    try {
                        if (card == null) {                       // 只有规则命中 -> 用规则字段
                            card = new JSONObject();
                            JSONObject f0 = ruleHit.optJSONObject("fields");
                            if (f0 != null) {
                                for (java.util.Iterator<String> it = f0.keys(); it.hasNext(); ) {
                                    String k = it.next(); card.put(k, f0.opt(k));
                                }
                            }
                            card.put("kind_name", ruleHit.optString("kind_name", "未知"));
                        }
                        JSONObject rf = (ruleHit == null) ? null : ruleHit.optJSONObject("fields");
                        _cmp.put("rule_name", ruleHit == null ? null : ruleHit.optString("rule"));
                        _cmp.put("rule_kind", ruleHit == null ? null : ruleHit.optString("kind_name"));
                        _cmp.put("rule_fields", rf);
                        _cmp.put("match", ruleHit != null
                                && safeEq(card.optString("price"), rf == null ? null : rf.optString("price")));
                        card.put("cmp", _cmp);
                    } catch (Exception ignored) {}
                    // ★价格稳定判定: 滴滴预约单价格会滚动(实测 64.46 -> 50.05),
                    //   读得太早会拿到中间价 -> 连续两次读到同价才认为价格定了
                    String priceNow = card.optString("price", "");
                    boolean stable = false;
                    if (priceNow != null && !priceNow.isEmpty()) {
                        if (priceNow.equals(lastPrice)) stable = true;
                        lastPrice = priceNow;
                    }
                    try { card.put("price_stable", stable); } catch (Exception ignored) {}
                    boolean lastTry = (i == cfg.cardRetries() - 1);
                    if (!stable && !lastTry) continue;   // 价格还在滚 -> 继续等(不急着上报)

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
                    // ★影子动作决策: 两条线路并行评估, **都只算不点**
                    //   act    = 线路A(内置默认动作规则, 滴滴专用)
                    //   act_b  = 线路B(调用者 POST /v1/action_rules 配的规则)
                    //   act_cmp= 两条线路的决策对比
                    try {
                        JSONObject actA = ActionEngine.decide(nodes, card, defaultActionRules());
                        JSONObject actB = ActionEngine.decide(nodes, card, cfg.actionRules());
                        JSONObject ac = new JSONObject();
                        ac.put("a", actA.optString("dec"));
                        ac.put("b", actB.optString("dec"));
                        ac.put("match", actA.optString("dec").equals(actB.optString("dec")));
                        card.put("act", actA);
                        card.put("act_b", actB);
                        card.put("act_cmp", ac);
                        card.put("shadow", true);   // ★恒为真: 本版本不执行任何点击
                    } catch (Exception ignored) {}
                    WatchBus.publish(WatchService.this, card);
                    takeShot(card);          // ★APK 自己截图存档, 不抢 GKD 的时间
                    Log.i(TAG, "卡片识别 价=" + card.optString("price")
                            + " 第" + (i + 1) + "次(" + ((i + 1) * cfg.cardDelayMs()) + "ms) 取到"
                            + " 距事件" + (SystemClock.elapsedRealtime() - lastContentAt) + "ms"
                            + " 来源=" + lastContentCls);
                    if (cfg.actionTrack()) startTrack(cfg, card);
                    return;
                }
            }
        }).start();
    }

    /**
     * APK 自己截图 —— **不依赖 GKD 快照**。
     * 用无障碍服务的 takeScreenshot(API30+) 直接截当前屏幕, 压成 JPEG 后 POST 给脚本侧
     * 存到 captures/ 目录(与脚本的存档图同目录, 报告站可直接引用)。
     * 为什么要做这个: 脚本的存档图走 GKD captureSnapshot, 会和 GKD 的点击抢时间
     *   (实测扑空率 5%→25%, actionDelay 调回 150ms 后 8%); APK 自己截图就没有这个竞争。
     * 另外数据本身(价格/距您/抬头等)APK 是直接读无障碍节点的 node.getText() 原文字, 从来不是 OCR。
     */
    private void takeShot(final JSONObject card) {
        try {
            takeScreenshot(Display.DEFAULT_DISPLAY, getMainExecutor(), new TakeScreenshotCallback() {
                @Override public void onSuccess(ScreenshotResult result) {
                    Bitmap bm = null;
                    try {
                        HardwareBuffer buf = result.getHardwareBuffer();
                        bm = Bitmap.wrapHardwareBuffer(buf, null);
                        buf.close();
                        if (bm == null) return;
                        Bitmap flat = bm.copy(Bitmap.Config.ARGB_8888, false);
                        ByteArrayOutputStream baos = new ByteArrayOutputStream();
                        flat.compress(Bitmap.CompressFormat.JPEG, 72, baos);
                        flat.recycle(); bm.recycle();
                        String url = "http://127.0.0.1:8765/apk_shot"
                                + "?price=" + java.net.URLEncoder.encode(card.optString("price", ""), "UTF-8")
                                + "&ts=" + card.optLong("ts", 0);
                        WatchBus.postBinaryAsync(url, baos.toByteArray(), "image/jpeg");
                    } catch (Exception e) {
                        Log.w(TAG, "截图处理失败: " + e);
                    }
                }

                @Override public void onFailure(int reason) {
                    Log.w(TAG, "截图失败 reason=" + reason);
                }
            });
        } catch (Exception e) {
            Log.w(TAG, "takeScreenshot 不可用: " + e);
        }
    }

    /**
     * 跟进复查：卡片出现后持续盯两个按钮的状态（倒计时中 -> 可点），
     * 状态一变就上报一条 kind=card_action 事件（影子模式，只报不点）。
     * 目的：搞清楚"抢单按钮到底是哪个节点、在什么时刻变成可点"，
     *       这是把点击从 GKD 切到 APK 的必要前置数据。
     */
    private void startTrack(final WatchConfig cfg, final JSONObject card) {
        if (!tracking.compareAndSet(false, true)) return;   // 已有卡在跟踪
        final String p0 = card.optString("price", "");
        final long t0 = System.currentTimeMillis();
        new Thread(new Runnable() {
            @Override public void run() {
                String lastState = "";
                String lastP = null;
                try {
                    for (int i = 0; i < 60; i++) {          // 最多 60 次(事件可提前唤醒)
                        // 等 400ms, 但事件来了立刻醒
                        synchronized (trackLock) {
                            try { trackLock.wait(400); } catch (Exception ignored) {}
                        }
                        // 最小间隔 80ms: 事件洪水时别把 CPU 打满
                        long gap = SystemClock.elapsedRealtime() - lastTrackCheck;
                        if (gap < 80) {
                            try { Thread.sleep(80 - gap); } catch (Exception e) { break; }
                        }
                        lastTrackCheck = SystemClock.elapsedRealtime();
                        JSONArray nodes = dumpTree();
                        boolean hasCard = false;
                        for (int j = 0; j < nodes.length(); j++) {
                            JSONObject n = nodes.optJSONObject(j);
                            if (n != null && n.optString("v", "").endsWith("order_review_fragment")) {
                                hasCard = true; break;
                            }
                        }
                        if (!hasCard) {
                            publishAction(card, p0, t0, "card_gone",
                                    new JSONObject(), new JSONObject(), false);
                            break;
                        }
                        String state = ActionEngine.buttonState(nodes);
                        JSONObject cur = findCard(nodes);
                        String cp = cur == null ? "" : cur.optString("price", "");
                        boolean pstable = false;
                        if (cp != null && !cp.isEmpty()) {
                            if (cp.equals(lastP)) pstable = true;
                            lastP = cp;
                        }
                        try { if (cur != null) cur.put("price_stable", pstable); } catch (Exception ignored) {}
                        if (!state.equals(lastState)) {
                            lastState = state;
                            JSONObject decA = ActionEngine.decide(nodes, cur == null ? card : cur, defaultActionRules());
                            JSONObject decB = ActionEngine.decide(nodes, cur == null ? card : cur, cfg.actionRules());
                            publishAction(card, p0, t0, state, decA, decB,
                                    ActionEngine.rightClickable(nodes));
                        }
                    }
                } finally {
                    tracking.set(false);
                }
            }
        }).start();
    }

    /** 上报一次按钮状态变化(带双线路决策) */
    private void publishAction(JSONObject card, String price, long t0, String state,
                               JSONObject decA, JSONObject decB, boolean rightClickable) {
        JSONObject p = new JSONObject();
        try {
            p.put("kind", "card_action");
            p.put("ts", System.currentTimeMillis());
            p.put("origin", getPackageName());
            p.put("pkg", card.optString("pkg", ""));
            p.put("price", price);
            p.put("state", state);
            p.put("dec", decA.optString("dec"));
            p.put("dec_b", decB.optString("dec"));
            p.put("match", decA.optString("dec").equals(decB.optString("dec")));
            p.put("target_vid", decA.optString("target_vid"));
            p.put("reason", decA.optString("reason"));
            p.put("price_stable", decA.optBoolean("price_stable", true));
            p.put("right_clickable", rightClickable);
            p.put("since_card_ms", System.currentTimeMillis() - t0);
            p.put("shadow", true);
        } catch (Exception ignored) {}
        WatchBus.publish(this, p);
    }

    /** 内置默认动作规则(线路A) */
    private static org.json.JSONArray defaultActionRules() {
        return WatchConfig.defaultActionRules();
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

    private static boolean safeEq(String a, String b) {
        if (a == null && b == null) return true;
        if (a == null || b == null) return false;
        try { return Math.abs(Double.parseDouble(a) - Double.parseDouble(b)) < 0.001; }
        catch (Exception e) { return a.equals(b); }
    }

    private JSONArray dumpTree() {
        JSONArray out = new JSONArray();
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return out;
        Deque<AccessibilityNodeInfo> q = new ArrayDeque<>();
        Deque<Integer> pq = new ArrayDeque<>();     // 父节点在 out 里的下标(-1=根)
        q.add(root);
        pq.add(-1);
        int n = 0;
        while (!q.isEmpty() && n < MAX_NODES) {
            AccessibilityNodeInfo node = q.poll();
            Integer pid = pq.poll();
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
                o.put("pid", pid == null ? -1 : pid);   // 父节点下标: 用于算 preorder(复刻 GKD <<n)
                out.put(o);
                int myIdx = n;
                n++;
                for (int i = 0; i < node.getChildCount(); i++) {
                    AccessibilityNodeInfo ch = node.getChild(i);
                    if (ch != null) { q.add(ch); pq.add(myIdx); }
                }
            } catch (Exception ignored) {}
        }
        return out;
    }
}
