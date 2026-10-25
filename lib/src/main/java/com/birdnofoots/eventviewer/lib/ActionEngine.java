package com.birdnofoots.eventviewer.lib;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * 影子动作引擎 —— **只算不点**。
 *
 * 两条线路并行评估, 都只产出决策(绝不执行任何点击):
 *   线路A: 内置默认动作规则(WatchConfig.DEFAULT_ACTION_RULES) —— 滴滴车主专用
 *   线路B: 调用者通过 POST /v1/action_rules 配置的规则
 * 两条线路走**同一个引擎**, 所以结果天然可比; 事件里带 act / act_b / act_cmp 三个字段。
 *
 * 规则格式(JSON 数组):
 * [
 *   {"name":"didi_no_grab",
 *    "when":{"all":[{"field":"price","lt":80},
 *                   {"node":"title_left_btn","text":"不抢"}]},
 *    "target":"order_review_left_btn", "dec":"tap_no", "need_stable":true},
 *   {"name":"didi_grab",
 *    "when":{"all":[{"field":"price","gt":99}]},
 *    "target":"order_review_right_btn", "dec":"tap_grab", "need_clickable":true}
 * ]
 *
 * 条件种类:
 *   {"field":"price"|"dist_me"|"dist_all", "lt"|"gt"|"lte"|"gte": 数值}
 *   {"node":"vid后缀", "text":"文字"}      节点文本等于
 *   {"node":"vid后缀", "exists":true}      节点存在
 *   {"node":"vid后缀", "clickable":true}   节点可点
 * 门槛比较按 GKD 口径**整数截断**(79.99->79, 80.00->80)
 */
public final class ActionEngine {

    public static final String DEC_NONE = "none";           // 不动作
    public static final String DEC_TAP_NO = "tap_no";       // 应点「不抢」
    public static final String DEC_TAP_GRAB = "tap_grab";   // 应点「抢单」
    public static final String DEC_WAIT = "wait";           // 条件满足, 但按钮此刻不可点
    public static final String DEC_WAIT_PRICE = "wait_price"; // 价格还没稳定, 等稳定再判

    private ActionEngine() {}

    /** 评估一套规则; rules 为 null/空 -> 不匹配任何动作 */
    public static JSONObject decide(JSONArray nodes, JSONObject card, JSONArray rules) {
        JSONObject o = new JSONObject();
        StringBuilder why = new StringBuilder();
        boolean unstable = false;
        try {
            o.put("dec", DEC_NONE);
            o.put("target_vid", "");
            o.put("reason", "无规则命中");
            if (nodes == null || nodes.length() == 0 || rules == null || rules.length() == 0) {
                o.put("reason", "没有可用规则");
                return o;
            }
            for (int i = 0; i < rules.length(); i++) {
                JSONObject r = rules.optJSONObject(i);
                if (r == null) continue;
                StringBuilder rw = new StringBuilder();
                boolean ok = matchWhen(r.optJSONObject("when"), nodes, card, rw);
                boolean needStable = r.optBoolean("need_stable", false);
                if (needStable && !isPriceStable(card)) {
                    unstable = true;
                    why.append(r.optString("name", "?")).append("(等价格稳定) ");
                    continue;
                }
                if (!ok) { why.append(r.optString("name", "?")).append("(不满足) "); continue; }
                String dec = r.optString("dec", DEC_NONE);
                String target = r.optString("target", "");
                o.put("dec", dec);
                o.put("rule", r.optString("name", "?"));
                o.put("target_vid", target);
                if (r.optBoolean("need_clickable", false) && !targetClickable(nodes, target)) {
                    o.put("dec", DEC_WAIT);
                    o.put("reason", "规则命中但按钮此刻不可点(" + rw + ")");
                    return o;
                }
                o.put("reason", "规则" + r.optString("name", "?") + " 命中: " + rw);
                return o;
            }
            if (unstable) { o.put("dec", DEC_WAIT_PRICE); o.put("reason", why.toString().trim()); }
            else o.put("reason", why.length() > 0 ? why.toString().trim() : "无规则命中");
            return o;
        } catch (Exception e) {
            try { o.put("dec", DEC_NONE); o.put("reason", "异常:" + e); } catch (Exception ignored) {}
            return o;
        }
    }

    private static boolean matchWhen(JSONObject when, JSONArray nodes, JSONObject card, StringBuilder why) {
        if (when == null || when.length() == 0) return false;
        JSONArray list = when.optJSONArray("all");
        boolean anyMode = false;
        if (list == null) { list = when.optJSONArray("any"); anyMode = true; }
        if (list == null) return false;
        boolean anyOk = false;
        for (int i = 0; i < list.length(); i++) {
            JSONObject c = list.optJSONObject(i);
            if (c == null) continue;
            StringBuilder cw = new StringBuilder();
            boolean ok = matchCond(c, nodes, card, cw);
            if (ok) anyOk = true;
            why.append(cw);
            if (!anyMode && !ok) return false;   // all 模式: 任一不满足即失败
        }
        return anyMode ? anyOk : true;
    }

    private static boolean matchCond(JSONObject c, JSONArray nodes, JSONObject card, StringBuilder why) {
        String fld = c.optString("field", "");
        if (!fld.isEmpty()) {
            Double v = fieldValue(fld, card);
            String op = ""; Double rhs = null;
            if (c.has("lt")) { op = "<"; rhs = c.optDouble("lt"); }
            else if (c.has("gt")) { op = ">"; rhs = c.optDouble("gt"); }
            else if (c.has("lte")) { op = "<="; rhs = c.optDouble("lte"); }
            else if (c.has("gte")) { op = ">="; rhs = c.optDouble("gte"); }
            else if (c.has("eq")) { op = "="; rhs = c.optDouble("eq"); }
            boolean ok = false;
            if (v != null && rhs != null) {
                if ("<".equals(op)) ok = v < rhs;
                else if (">".equals(op)) ok = v > rhs;
                else if ("<=".equals(op)) ok = v <= rhs;
                else if (">=".equals(op)) ok = v >= rhs;
                else ok = Math.abs(v - rhs) < 0.001;
            }
            why.append(fld).append("=").append(v).append(op).append(rhs).append(" ");
            return ok;
        }
        String node = c.optString("node", "");
        if (!node.isEmpty()) {
            JSONObject n = find(nodes, node);
            if (c.has("exists")) {
                boolean ok = (n != null) == c.optBoolean("exists", true);
                why.append(node).append("存在=").append(n != null).append(" ");
                return ok;
            }
            if (c.has("clickable")) {
                boolean ck = n != null && n.optBoolean("k", false);
                boolean ok = ck == c.optBoolean("clickable", true);
                why.append(node).append(".clickable=").append(ck).append(" ");
                return ok;
            }
            if (c.has("text")) {
                String t = n == null ? "" : n.optString("t", "");
                boolean ok = t.equals(c.optString("text", ""));
                why.append(node).append(".text='").append(t).append("' ");
                return ok;
            }
        }
        return false;
    }

    /** 字段取值; price 走 GKD 整数截断口径 */
    private static Double fieldValue(String fld, JSONObject card) {
        if (card == null) return null;
        if ("price".equals(fld)) {
            boolean[] ok = new boolean[1];
            int v = gkdInt(card.optString("price", ""), ok);
            return ok[0] ? (double) v : null;
        }
        if (!card.has(fld)) return null;
        try { return card.optDouble(fld); } catch (Exception e) { return null; }
    }

    /** GKD 口径的取整: text.substring(0,text.indexOf('.')).toInt() —— 没有 '.' 时 GKD 表达式会失败 */
    static int gkdInt(String t, boolean[] ok) {
        ok[0] = false;
        if (t == null) return 0;
        int i = t.indexOf('.');
        if (i <= 0) return 0;
        try {
            int v = Integer.parseInt(t.substring(0, i).trim());
            ok[0] = true;
            return v;
        } catch (Exception e) {
            return 0;
        }
    }

    static boolean isPriceStable(JSONObject card) {
        if (card == null) return true;
        if (!card.has("price_stable")) return true;      // 没这个字段 -> 不拦
        return card.optBoolean("price_stable", true);
    }

    static JSONObject find(JSONArray nodes, String vidSuffix) {
        if (nodes == null) return null;
        for (int i = 0; i < nodes.length(); i++) {
            JSONObject n = nodes.optJSONObject(i);
            if (n == null) continue;
            if (n.optString("v", "").endsWith(vidSuffix)) return n;
        }
        return null;
    }

    /** 目标节点(或其子孙里 text 节点)是否可点 */
    static boolean targetClickable(JSONArray nodes, String vidSuffix) {
        if (vidSuffix == null || vidSuffix.isEmpty()) return true;
        JSONObject n = find(nodes, vidSuffix);
        if (n == null) return false;
        if (n.optBoolean("k", false)) return true;
        // 容器不可点但里面的文字节点可点也算(滴滴按钮: 容器clickable, 文字不可点, 反之亦然)
        for (int i = 0; i < nodes.length(); i++) {
            JSONObject m = nodes.optJSONObject(i);
            if (m == null) continue;
            if (m.optBoolean("k", false)) {
                String v = m.optString("v", "");
                if (v.contains(vidSuffix) || vidSuffix.contains(v)) return true;
            }
        }
        return false;
    }

    /**
     * 按钮状态快照(给"跟进复查"用), 紧凑字符串便于比较是否变化:
     *   右文字|右容器可点|右文字可点|左文字|左容器可点|左文字可点
     * 滴滴按钮结构: order_review_right_btn(容器) / title_right_btn(文字), clickable 归属两者之一
     */
    static String buttonState(JSONArray nodes) {
        if (nodes == null) return "";
        JSONObject rb = find(nodes, "order_review_right_btn");
        JSONObject tr = find(nodes, "title_right_btn");
        JSONObject lb = find(nodes, "order_review_left_btn");
        JSONObject tl = find(nodes, "title_left_btn");
        String rt = tr == null ? "" : tr.optString("t", "");
        String lt = tl == null ? "" : tl.optString("t", "");
        boolean rc = rb != null && rb.optBoolean("k", false);
        boolean tc = tr != null && tr.optBoolean("k", false);
        boolean lc = lb != null && lb.optBoolean("k", false);
        boolean ltc = tl != null && tl.optBoolean("k", false);
        return rt + "|" + rc + "|" + tc + "|" + lt + "|" + lc + "|" + ltc;
    }

    /** 右按钮是否任一节点可点 */
    static boolean rightClickable(JSONArray nodes) {
        JSONObject rb = find(nodes, "order_review_right_btn");
        JSONObject tr = find(nodes, "title_right_btn");
        return (rb != null && rb.optBoolean("k", false))
                || (tr != null && tr.optBoolean("k", false));
    }
}
