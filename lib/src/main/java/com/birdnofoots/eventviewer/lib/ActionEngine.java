package com.birdnofoots.eventviewer.lib;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * 影子动作引擎 —— 1:1 复刻 GKD 的两条规则(组0 不抢 / 组1 抢单)，**只算不点**。
 *
 * 目的：让 APK 自己算出"该不该点、点什么"，与 GKD 的实际动作逐条对账；
 *       零误判之后才考虑把点击切到 APK（GKD 下线）。
 *
 * 复刻口径(与 gkd/didi_rule.json5 对齐)：
 *   组0 不抢：title_left_btn.text == '不抢'  且  价 < 80
 *             → 目标 order_review_left_btn
 *   组1 抢单：价 > 99（整数截断，等价"价 >= 100"）
 *             → 目标 order_review_right_btn；**按钮 clickable=true 才算真能点**
 *               （倒计时"3秒后开始抢单"期间是 false，点不动）
 *
 * 门槛比较一律按 GKD 的"整数截断"：79.99→79 放行，80.00→80 拒(不抢侧)。
 */
public final class ActionEngine {

    public static final String DEC_NONE = "none";         // 不动作
    public static final String DEC_TAP_NO = "tap_no";     // 点"不抢"
    public static final String DEC_TAP_GRAB = "tap_grab"; // 点"抢单"
    public static final String DEC_WAIT = "wait";         // 条件满足，但按钮此刻还不可点

    private ActionEngine() {}

    /** 对一张卡片算出动作决策；card 可以是 null（只有节点树） */
    public static JSONObject decide(JSONArray nodes, JSONObject card) {
        JSONObject o = new JSONObject();
        try {
            o.put("dec", DEC_NONE);
            o.put("grp", 0);
            o.put("target_vid", "");
            o.put("reason", "无卡片");

            if (nodes == null || nodes.length() == 0) return o;

            // ---- 取节点 ----
            JSONObject titleLeft = find(nodes, "title_left_btn");
            JSONObject leftBtn = find(nodes, "order_review_left_btn");
            JSONObject rightBtn = find(nodes, "order_review_right_btn");
            JSONObject titleRight = find(nodes, "title_right_btn");

            String leftText = titleLeft == null ? "" : titleLeft.optString("t", "");
            String rightText = titleRight == null ? "" : titleRight.optString("t", "");
            boolean rightClickable = (rightBtn != null && rightBtn.optBoolean("k", false))
                    || (titleRight != null && titleRight.optBoolean("k", false));
            boolean leftClickable = leftBtn != null && leftBtn.optBoolean("k", false);

            // ---- 取价(GKD 口径: 没有小数点会导致 GKD 表达式失败) ----
            String priceText = card == null ? "" : card.optString("price", "");
            if (priceText.isEmpty()) {
                JSONObject pn = find(nodes, "price_info_card_title");
                priceText = pn == null ? "" : pn.optString("t", "");
            }
            boolean[] ok = new boolean[1];
            int priceInt = gkdInt(priceText, ok);

            o.put("price_text", priceText);
            if (ok[0]) o.put("price_int", priceInt); else o.put("price_int", JSONObject.NULL);
            o.put("left_text", leftText);
            o.put("right_text", rightText);
            o.put("right_clickable", rightClickable);
            o.put("left_clickable", leftClickable);
            if (card != null && card.has("dist_me")) o.put("dist_me", card.optDouble("dist_me"));
            if (card != null && card.has("dist_all")) o.put("dist_all", card.optDouble("dist_all"));

            // ---- 结构链校验: 复刻 GKD 的 <<(10..16) 先序偏移链 ----
            // APK 的树带 pid(父在输出数组里的下标), 这里自己算 preorder 索引,
            // 检查"价格节点"是不是"卡片容器"的子孙且偏移落在 9..15(=<<10..16)。
            // 离线复算 222/222 张真卡偏移恒为 12, 所以这条链等价于"价格属于这张卡"。
            String chain = chainState(nodes);
            o.put("chain", chain);

            boolean isBooking = leftBtn != null;   // 有"不抢/抢预约单"左按钮 = 预约单卡

            // ---- 组0：不抢（价 < 80）----
            if (isBooking && "不抢".equals(leftText) && ok[0] && priceInt < 80) {
                o.put("dec", DEC_TAP_NO);
                o.put("grp", 0);
                o.put("target_vid", "order_review_left_btn");
                o.put("reason", "价" + priceInt + "<80 且有'不抢'按钮");
                return o;
            }

            // ---- 组1：抢单（价 > 99）----
            if (isBooking && ok[0] && priceInt > 99) {
                o.put("grp", 1);
                if (rightClickable) {
                    o.put("dec", DEC_TAP_GRAB);
                    o.put("target_vid", "order_review_right_btn");
                    o.put("reason", "价" + priceInt + ">99 且抢单按钮可点(" + rightText + ")");
                } else {
                    o.put("dec", DEC_WAIT);
                    o.put("target_vid", "order_review_right_btn");
                    o.put("reason", "价" + priceInt + ">99 但抢单按钮此刻不可点(" + rightText + ")");
                }
                return o;
            }

            o.put("dec", DEC_NONE);
            o.put("reason", ok[0] ? ("价" + priceInt + " 不满足门槛") : "价格无法按 GKD 口径取整");
            return o;
        } catch (Exception e) {
            try { o.put("dec", DEC_NONE); o.put("reason", "异常:" + e); } catch (Exception ignored) {}
            return o;
        }
    }

    /**
     * 只看按钮状态(给"跟进复查"用)：返回 "text|clickable" 形式的快照。
     * clickable 任一节点为 true 即算可点。
     */
    public static String buttonState(JSONArray nodes) {
        if (nodes == null) return "";
        JSONObject rightBtn = find(nodes, "order_review_right_btn");
        JSONObject titleRight = find(nodes, "title_right_btn");
        String rightText = titleRight == null ? "" : titleRight.optString("t", "");
        boolean rc = (rightBtn != null && rightBtn.optBoolean("k", false))
                || (titleRight != null && titleRight.optBoolean("k", false));
        JSONObject titleLeft = find(nodes, "title_left_btn");
        String leftText = titleLeft == null ? "" : titleLeft.optString("t", "");
        JSONObject leftBtn = find(nodes, "order_review_left_btn");
        boolean lc = leftBtn != null && leftBtn.optBoolean("k", false);
        return rightText + "|" + rc + "|" + leftText + "|" + lc;
    }

    /** GKD 口径的取整: text.substring(0,text.indexOf('.')).toInt() —— 没有 '.' 时 GKD 表达式会失败 */
    static int gkdInt(String t, boolean[] ok) {
        ok[0] = false;
        if (t == null) return 0;
        int i = t.indexOf('.');
        if (i <= 0) return 0;          // GKD: indexOf('.')=-1 -> substring 抛异常 -> 规则不匹配
        try {
            int v = Integer.parseInt(t.substring(0, i).trim());
            ok[0] = true;
            return v;
        } catch (Exception e) {
            return 0;
        }
    }

    /** 结构链状态: ok / off=N / no_fragment / no_price */
    static String chainState(JSONArray nodes) {
        int frag = -1, price = -1;
        for (int i = 0; i < nodes.length(); i++) {
            JSONObject n = nodes.optJSONObject(i);
            if (n == null) continue;
            String v = n.optString("v", "");
            if (frag < 0 && v.endsWith("order_review_fragment")) frag = i;
            if (price < 0 && v.endsWith("price_info_card_title")) price = i;
        }
        if (frag < 0) return "no_fragment";
        if (price < 0) return "no_price";
        int off = preOrderIndex(nodes, price) - preOrderIndex(nodes, frag);
        if (off >= 9 && off <= 15) return "ok";
        return "off=" + off;
    }

    /** 深度优先先序索引(根=0) */
    static int preOrderIndex(JSONArray nodes, int target) {
        int n = nodes.length();
        if (target < 0 || target >= n) return -1;
        // 建子表
        java.util.List<java.util.List<Integer>> kids = new java.util.ArrayList<>();
        for (int i = 0; i < n; i++) kids.add(new java.util.ArrayList<Integer>());
        java.util.List<Integer> roots = new java.util.ArrayList<>();
        for (int i = 0; i < n; i++) {
            JSONObject o = nodes.optJSONObject(i);
            if (o == null) continue;
            int p = o.optInt("pid", -1);
            if (p >= 0 && p < i) kids.get(p).add(i);
            else roots.add(i);
        }
        int[] idx = new int[n];
        java.util.Arrays.fill(idx, -1);
        int[] counter = new int[1];
        java.util.Deque<int[]> st = new java.util.ArrayDeque<>();
        for (int i = roots.size() - 1; i >= 0; i--) st.push(new int[]{roots.get(i), 0});
        while (!st.isEmpty()) {
            int[] cur = st.pop();
            int node = cur[0];
            idx[node] = counter[0]++;
            if (node == target) return idx[node];
            java.util.List<Integer> ch = kids.get(node);
            for (int i = ch.size() - 1; i >= 0; i--) st.push(new int[]{ch.get(i), cur[1] + 1});
        }
        return idx[target];
    }

    /** 取第一个 vid 结尾匹配且有 text 的节点 */
    static JSONObject find(JSONArray nodes, String vidSuffix) {
        for (int i = 0; i < nodes.length(); i++) {
            JSONObject n = nodes.optJSONObject(i);
            if (n == null) continue;
            if (n.optString("v", "").endsWith(vidSuffix)) return n;
        }
        return null;
    }
}
