package com.birdnofoots.eventviewer.lib;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** 配置: 监听哪些应用/哪个界面, 事件要回调给谁 */
public class WatchConfig {
    private static final String PREFS = "didiwatch";
    /** 默认不监听任何应用 —— 由用户在界面里选, 或 POST /v1/config 指定 */
    public static final String DEFAULT_PKG = "";
    public static final String DEFAULT_ACT = "";
    /** 本机 API 首选端口(只监听 127.0.0.1); 被占用时会自动换并存起来 */
    public static final int API_PORT = 8766;

    private final SharedPreferences sp;

    private WatchConfig(SharedPreferences sp) { this.sp = sp; }

    public static WatchConfig get(Context c) {
        return new WatchConfig(c.getSharedPreferences(PREFS, Context.MODE_PRIVATE));
    }

    /** 监听目标: 逗号分隔的包名, 可以多个 */
    public String pkg() { return sp.getString("pkg", DEFAULT_PKG); }
    public void setPkg(String v) { sp.edit().putString("pkg", v == null ? "" : v.trim()).apply(); }

    public List<String> pkgs() {
        List<String> out = new ArrayList<>();
        for (String s : pkg().split(",")) {
            String t = s.trim();
            if (!t.isEmpty() && !out.contains(t)) out.add(t);
        }
        return out;
    }

    public void addPkg(String p) {
        List<String> l = pkgs();
        if (!l.contains(p)) l.add(p);
        setPkg(String.join(",", l));
    }

    public void removePkg(String p) {
        List<String> l = pkgs();
        l.remove(p);
        setPkg(String.join(",", l));
    }

    /** 界面名包含此串才算命中; 留空 = 该应用的任何界面 */
    public String act() { return sp.getString("act", DEFAULT_ACT); }
    public void setAct(String v) { sp.edit().putString("act", v == null ? "" : v).apply(); }

    /** 影子模式: 识别订单卡(只上报不点击) */
    public boolean cardDetect() { return sp.getBoolean("card_detect", true); }
    public void setCardDetect(boolean b) { sp.edit().putBoolean("card_detect", b).apply(); }
    /** 事件到后等多少毫秒再取树(卡片晚于事件渲染) */
    public int cardDelayMs() { return sp.getInt("card_delay_ms", 100); }
    public void setCardDelayMs(int v) { sp.edit().putInt("card_delay_ms", v).apply(); }
    /** 没看到卡片最多试几次(每次间隔 cardDelayMs) */
    public int cardRetries() { return sp.getInt("card_retries", 3); }
    public void setCardRetries(int v) { sp.edit().putInt("card_retries", v).apply(); }

    /** 影子模式: 算出动作决策但不真点(阶段2 默认开, 切主前才关) */
    public boolean shadowAction() { return sp.getBoolean("shadow_action", true); }
    public void setShadowAction(boolean b) { sp.edit().putBoolean("shadow_action", b).apply(); }
    /**
     * APK 真实点击开关 —— **默认 false**。
     * 只有显式开启(POST /v1/config {"tap_enabled":true} 或 didi tap apk)才会真的点。
     * 注意: 开了之后 shadow_action 自动视为 false(点击与影子互斥)。
     */
    public boolean tapEnabled() { return sp.getBoolean("tap_enabled", false); }
    public void setTapEnabled(boolean b) { sp.edit().putBoolean("tap_enabled", b).apply(); }
    /** 卡片出现后跟踪按钮状态(倒计时 -> 可点), 状态一变就上报 */
    public boolean actionTrack() { return sp.getBoolean("action_track", true); }
    public void setActionTrack(boolean b) { sp.edit().putBoolean("action_track", b).apply(); }

    public boolean includeTree() { return sp.getBoolean("include_tree", false); }
    public void setIncludeTree(boolean b) { sp.edit().putBoolean("include_tree", b).apply(); }

    public Set<String> callbacks() { return new HashSet<>(sp.getStringSet("callbacks", new HashSet<String>())); }
    public void setCallbacks(Set<String> s) { sp.edit().putStringSet("callbacks", new HashSet<>(s)).apply(); }

    public int apiPort() { return sp.getInt("api_port", API_PORT); }
    public void setApiPort(int p) { sp.edit().putInt("api_port", p).apply(); }

    /** 默认规则 = 滴滴订单卡(和写死逻辑等价, 用于 A/B 对比) */
    public static final String DEFAULT_RULES =
        "[{" +
        "\"name\":\"didi_card\"," +
        "\"pkg\":\"com.sdu.didi.gsui\"," +
        "\"require_all\":[\"order_review_fragment\"]," +
        "\"require_any\":[\"price_info_card_title\",\"order_review_head_tag_view\"]," +
        "\"fields\":{\"price\":\"price_info_card_title\",\"tag\":\"order_review_head_tag_view\"," +
        "\"time\":\"journey_value[0]\",\"dist_me\":\"journey_value[1]\"," +
        "\"dist_all\":\"journey_value[2]\",\"addr_from\":\"address_name[0]\",\"addr_to\":\"address_name[1]\"}," +
        "\"kinds\":[{\"name\":\"预约单\",\"require\":[\"order_review_left_btn\"]}," +
        "{\"name\":\"实时单\",\"require_any\":[\"order_review_head_tag_view\"]}]" +
        "}]";

    public JSONArray rules() {
        String j = sp.getString("rules", DEFAULT_RULES);
        try { return new org.json.JSONArray(j); } catch (Exception e) {
            try { return new org.json.JSONArray(DEFAULT_RULES); } catch (Exception e2) { return null; }
        }
    }
    public void setRules(String json) { sp.edit().putString("rules", json).apply(); }

    /**
     * 内置默认动作规则(线路A) —— 滴滴车主专用。
     * ★只是算决策, **不执行任何点击**(影子模式)。
     * 线路B = 调用者 POST /v1/action_rules 配置的规则, 同一套引擎评估。
     * 用 org.json API 程序化构造(不用 JSON 字符串), 绝对不会踩转义/解析的坑。
     */
    public static JSONArray defaultActionRules() {
        JSONArray arr = new JSONArray();
        try {
            JSONObject g0 = new JSONObject();          // 组0: 不抢(价<80 且有「不抢」按钮)
            g0.put("name", "didi_no_grab");
            JSONArray c0 = new JSONArray();
            JSONObject c0a = new JSONObject(); c0a.put("field", "price"); c0a.put("lt", 80);
            JSONObject c0b = new JSONObject(); c0b.put("node", "title_left_btn"); c0b.put("text", "不抢");
            c0.put(c0a); c0.put(c0b);
            JSONObject w0 = new JSONObject(); w0.put("all", c0);
            g0.put("when", w0);
            g0.put("target", "order_review_left_btn");
            g0.put("dec", "tap_no");
            g0.put("need_stable", true);
            arr.put(g0);

            JSONObject g1 = new JSONObject();          // 组1: 抢单(价>99 且 距<20km, 且按钮可点)
            g1.put("name", "didi_grab");
            JSONArray c1 = new JSONArray();
            JSONObject c1a = new JSONObject(); c1a.put("field", "price"); c1a.put("gt", 99);
            JSONObject c1b = new JSONObject(); c1b.put("field", "dist_me"); c1b.put("lt", 20);
            c1.put(c1a); c1.put(c1b);   // ★距离门槛: 距您 < 20km(dist_me 已折算成 km)
            JSONObject w1 = new JSONObject(); w1.put("all", c1);
            g1.put("when", w1);
            g1.put("target", "order_review_right_btn");
            g1.put("dec", "tap_grab");
            g1.put("need_clickable", true);
            g1.put("need_stable", true);
            arr.put(g1);
        } catch (Exception ignored) {}
        return arr;
    }

    /** 线路B: 调用者可写的动作规则(没配置就用内置默认) */
    public JSONArray actionRules() {
        String j = sp.getString("action_rules", "");
        if (j == null || j.trim().isEmpty()) {
            return defaultActionRules();
        }
        try { return new org.json.JSONArray(j); }
        catch (Exception e) {
            return defaultActionRules();
        }
    }
    public void setActionRules(String json) { sp.edit().putString("action_rules", json).apply(); }

    public String token() { return sp.getString("token", ""); }
    public void setToken(String v) { sp.edit().putString("token", v).apply(); }
}
