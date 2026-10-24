package com.birdnofoots.eventviewer.lib;

import android.content.Context;
import android.content.SharedPreferences;

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

    public boolean includeTree() { return sp.getBoolean("include_tree", false); }
    public void setIncludeTree(boolean b) { sp.edit().putBoolean("include_tree", b).apply(); }

    public Set<String> callbacks() { return new HashSet<>(sp.getStringSet("callbacks", new HashSet<String>())); }
    public void setCallbacks(Set<String> s) { sp.edit().putStringSet("callbacks", new HashSet<>(s)).apply(); }

    public int apiPort() { return sp.getInt("api_port", API_PORT); }
    public void setApiPort(int p) { sp.edit().putInt("api_port", p).apply(); }

    public String token() { return sp.getString("token", ""); }
    public void setToken(String v) { sp.edit().putString("token", v).apply(); }
}
