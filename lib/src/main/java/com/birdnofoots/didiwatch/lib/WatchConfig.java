package com.birdnofoots.didiwatch.lib;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.HashSet;
import java.util.Set;

/** 配置: 监听哪个应用/哪个界面, 事件要回调给谁 */
public class WatchConfig {
    private static final String PREFS = "didiwatch";
    public static final String DEFAULT_PKG = "com.sdu.didi.gsui";
    public static final String DEFAULT_ACT = "BroadOrderActivity";
    /** 本机 API 首选端口(只监听 127.0.0.1); 被占用时会自动换并存起来 */
    public static final int API_PORT = 8766;

    private final SharedPreferences sp;

    private WatchConfig(SharedPreferences sp) { this.sp = sp; }

    public static WatchConfig get(Context c) {
        return new WatchConfig(c.getSharedPreferences(PREFS, Context.MODE_PRIVATE));
    }

    public String pkg() { return sp.getString("pkg", DEFAULT_PKG); }
    public void setPkg(String v) { sp.edit().putString("pkg", v).apply(); }

    public String act() { return sp.getString("act", DEFAULT_ACT); }
    public void setAct(String v) { sp.edit().putString("act", v).apply(); }

    public boolean includeTree() { return sp.getBoolean("include_tree", false); }
    public void setIncludeTree(boolean b) { sp.edit().putBoolean("include_tree", b).apply(); }

    public Set<String> callbacks() { return new HashSet<>(sp.getStringSet("callbacks", new HashSet<String>())); }
    public void setCallbacks(Set<String> s) { sp.edit().putStringSet("callbacks", new HashSet<>(s)).apply(); }

    public int apiPort() { return sp.getInt("api_port", API_PORT); }
    public void setApiPort(int p) { sp.edit().putInt("api_port", p).apply(); }

    public String token() { return sp.getString("token", ""); }
    public void setToken(String v) { sp.edit().putString("token", v).apply(); }
}
