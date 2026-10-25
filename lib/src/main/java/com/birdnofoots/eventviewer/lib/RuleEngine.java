package com.birdnofoots.eventviewer.lib;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 通用规则引擎 —— 让"识别某个界面出现了什么"这件事**不写死在代码里**。
 *
 * 规则示例(滴滴订单卡):
 * {
 *   "name": "didi_card",
 *   "pkg":  "com.sdu.didi.gsui",            // 省略 = 任意应用
 *   "act":  "",                             // 省略 = 任意界面
 *   "require_all": ["order_review_fragment"],                // 必须全有
 *   "require_any": ["price_info_card_title", "order_review_head_tag_view"],  // 至少其一
 *   "fields": {                                                   // 取值映射
 *      "price": "price_info_card_title",
 *      "tag":   "order_review_head_tag_view",
 *      "time":  "journey_value[0]",       // 带下标 = 按 y 排序后的第 N 个
 *      "dist_me":  "journey_value[1]",
 *      "dist_all": "journey_value[2]",
 *      "addr_from": "address_name[0]",
 *      "addr_to":   "address_name[1]"
 *   },
 *   "kinds": [                                                   // 卡种判定, 命中第一个
 *      {"name":"预约单","require":["order_review_left_btn"]},
 *      {"name":"实时单","require_any":["order_review_head_tag_view"]}
 *   ]
 * }
 */
public final class RuleEngine {

    /** 返回 null = 没命中任何规则; 否则返回 {rule, kind, fields{...}} */
    public static JSONObject evaluate(JSONArray nodes, String pkg, String cls, JSONArray rules) {
        if (nodes == null || nodes.length() == 0 || rules == null) return null;
        for (int i = 0; i < rules.length(); i++) {
            JSONObject r = rules.optJSONObject(i);
            if (r == null) continue;
            JSONObject hit = matchRule(nodes, pkg, cls, r);
            if (hit != null) return hit;
        }
        return null;
    }

    private static JSONObject matchRule(JSONArray nodes, String pkg, String cls, JSONObject r) {
        String rp = r.optString("pkg", "");
        if (!rp.isEmpty() && !rp.equals(pkg)) return null;
        String ra = r.optString("act", "");
        if (!ra.isEmpty() && (cls == null || !cls.contains(ra))) return null;

        JSONArray all = r.optJSONArray("require_all");
        JSONArray any = r.optJSONArray("require_any");
        if (all == null && any == null) return null;   // 至少要有一条约束, 否则太宽

        if (all != null) {
            for (int i = 0; i < all.length(); i++)
                if (!hasMarker(nodes, all.optString(i))) return null;
        }
        if (any != null && any.length() > 0) {
            boolean ok = false;
            for (int i = 0; i < any.length(); i++)
                if (hasMarker(nodes, any.optString(i))) { ok = true; break; }
            if (!ok) return null;
        }

        JSONObject out = new JSONObject();
        try {
            out.put("rule", r.optString("name", "未命名"));
            // 字段映射
            JSONObject fields = new JSONObject();
            JSONObject fmap = r.optJSONObject("fields");
            if (fmap != null) {
                java.util.Iterator<String> it = fmap.keys();
                while (it.hasNext()) {
                    String k = it.next();
                    String spec = fmap.optString(k, "");
                    String vid; int idx = -1;
                    int br = spec.indexOf('[');
                    if (br >= 0 && spec.endsWith("]")) {
                        vid = spec.substring(0, br);
                        try { idx = Integer.parseInt(spec.substring(br + 1, spec.length() - 1)); } catch (Exception e) { idx = -1; }
                    } else { vid = spec; }
                    String v = pick(nodes, vid, idx);
                    if (v == null || v.isEmpty()) continue;
                    if (k.startsWith("dist")) {
                        double km = parseKm(v);
                        if (!Double.isNaN(km)) fields.put(k, km);
                    } else {
                        fields.put(k, v);
                    }
                }
            }
            out.put("fields", fields);
            // 卡种
            JSONArray kinds = r.optJSONArray("kinds");
            String kind = null;
            if (kinds != null) {
                for (int i = 0; i < kinds.length(); i++) {
                    JSONObject k = kinds.optJSONObject(i);
                    if (k == null) continue;
                    JSONArray ka = k.optJSONArray("require");
                    JSONArray kb = k.optJSONArray("require_any");
                    boolean ok = true;
                    if (ka != null)
                        for (int j = 0; j < ka.length(); j++)
                            if (!hasMarker(nodes, ka.optString(j))) { ok = false; break; }
                    if (ok && kb != null && kb.length() > 0) {
                        boolean anyOk = false;
                        for (int j = 0; j < kb.length(); j++)
                            if (hasMarker(nodes, kb.optString(j))) { anyOk = true; break; }
                        ok = anyOk;
                    }
                    if (ok) { kind = k.optString("name", "未知"); break; }
                }
            }
            out.put("kind_name", kind == null ? "未知" : kind);
        } catch (Exception ignored) {}
        return out;
    }

    private static boolean hasMarker(JSONArray nodes, String marker) {
        if (marker == null || marker.isEmpty()) return false;
        for (int i = 0; i < nodes.length(); i++) {
            JSONObject n = nodes.optJSONObject(i);
            if (n != null && n.optString("v", "").endsWith(marker)) return true;
        }
        return false;
    }

    /** 取某 vid 的文本; idx<0 取第一个, 否则取按 y 排序后的第 idx 个 */
    private static String pick(JSONArray nodes, String vid, int idx) {
        List<Integer> order = new ArrayList<>();
        List<String> texts = new ArrayList<>();
        for (int i = 0; i < nodes.length(); i++) {
            JSONObject n = nodes.optJSONObject(i);
            if (n == null || !n.optString("v", "").endsWith(vid)) continue;
            JSONArray b = n.optJSONArray("b");
            double top = (b != null && b.length() > 1) ? b.optDouble(1, 0) : 0;
            order.add(order.size());
            texts.add(n.optString("t", ""));
            // 插入排序(按 top)
            int k = order.size() - 1;
            while (k > 0) {
                double tk = topOf(nodes, order.get(k)), tp = topOf(nodes, order.get(k - 1));
                if (tp <= tk) break;
                int o = order.get(k - 1); order.set(k - 1, order.get(k)); order.set(k, o);
                String t = texts.get(k - 1); texts.set(k - 1, texts.get(k)); texts.set(k, t);
                k--;
            }
        }
        if (texts.isEmpty()) return null;
        int i = (idx < 0 || idx >= texts.size()) ? 0 : idx;
        return texts.get(i);
    }

    private static double topOf(JSONArray nodes, int nodeIdx) {
        JSONArray b = nodes.optJSONObject(nodeIdx).optJSONArray("b");
        return (b != null && b.length() > 1) ? b.optDouble(1, 0) : 0;
    }

    /** 统一折算成 km(米制教训) */
    static double parseKm(String t) {
        if (t == null) return Double.NaN;
        String s = t.trim().replace(",", ".");
        Matcher m = Pattern.compile("(\\d+(?:\\.\\d+)?)").matcher(s);
        if (!m.find()) return Double.NaN;
        double v;
        try { v = Double.parseDouble(m.group(1)); } catch (Exception e) { return Double.NaN; }
        if (s.endsWith("m") && !s.endsWith("km")) v /= 1000.0;
        return v;
    }
}
