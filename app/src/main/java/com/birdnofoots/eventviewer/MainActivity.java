package com.birdnofoots.eventviewer;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.ContentObserver;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.graphics.Color;
import android.os.Bundle;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.accessibility.AccessibilityManager;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.birdnofoots.eventviewer.lib.WatchApiServer;
import com.birdnofoots.eventviewer.lib.WatchBus;
import com.birdnofoots.eventviewer.lib.WatchConfig;
import com.birdnofoots.eventviewer.lib.WatchService;

import org.json.JSONObject;

import java.security.SecureRandom;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** 状态页: 无障碍是否开启 / 本机 API 地址与 token / 最后事件 / 测试 */
public class MainActivity extends android.app.Activity {
    private static final String PREFS = "didiwatch";
    private TextView tvStatus, tvLast, tvApi;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private ContentObserver a11yObs;

    // 1 秒轮询: 授权与系统真正绑定之间有延迟(实测要移除再加回才重绑), 轮询兜底
    private final Runnable ticker = new Runnable() {
        @Override public void run() { refresh(); ui.postDelayed(this, 1000); }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        WatchConfig cfg = WatchConfig.get(this);
        WatchBus.init(this);
        WatchApiServer.start(this);
        if (cfg.token().isEmpty()) cfg.setToken(newToken());

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int p = (int) (16 * getResources().getDisplayMetrics().density);
        root.setPadding(p, p, p, p);

        tvStatus = new TextView(this);
        tvStatus.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        root.addView(tvStatus);

        tvApi = new TextView(this);
        tvApi.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        tvApi.setPadding(0, p, 0, p);
        root.addView(tvApi);

        tvLast = new TextView(this);
        tvLast.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        root.addView(tvLast);

        CheckBox cb = new CheckBox(this);
        cb.setText("附带节点树(默认关)");
        cb.setChecked(cfg.includeTree());
        cb.setOnCheckedChangeListener((b, on) -> cfg.setIncludeTree(on));
        root.addView(cb);

        root.addView(btn("选择监听的应用", v -> {
            startActivity(new Intent(this, AppPickerActivity.class));
        }));
        root.addView(btn("打开无障碍设置", v -> {
            try { startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)); }
            catch (Exception e) { tvLast.setText("打不开设置: " + e); }
        }));
        root.addView(btn("发一个测试事件", v -> {
            JSONObject o = new JSONObject();
            try {
                o.put("kind", "test");
                o.put("ts", System.currentTimeMillis());
                o.put("origin", getPackageName());
            } catch (Exception ignored) {}
            WatchBus.publish(this, o);
            tvLast.setText("已发测试事件 -> " + WatchBus.latest());
        }));
        root.addView(btn("订阅本机脚本回调", v -> {
            WatchConfig c = WatchConfig.get(this);
            java.util.Set<String> s = c.callbacks();
            s.add("http://127.0.0.1:8765/didi_event");
            c.setCallbacks(s);
            tvApi.setText(apiText(c));
            tvLast.setText("已订阅: http://127.0.0.1:8765/didi_event");
        }));

        TextView tip = new TextView(this);
        tip.setText("\n其它 App 怎么用:\n"
                + "  GET  http://127.0.0.1:<apiPort>/v1/health\n"
                + "  GET  http://127.0.0.1:<apiPort>/v1/events/latest\n"
                + "  POST http://127.0.0.1:<apiPort>/v1/subscribe  {\"url\":\"...\"}\n"
                + "  广播: action=com.birdnofoots.eventviewer.EVENT extra=json");
        tip.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        tip.setPadding(0, p, 0, 0);
        root.addView(tip);

        ScrollView sv = new ScrollView(this);
        sv.addView(root);
        setContentView(sv);
    }

    @Override
    protected void onResume() {
        super.onResume();
        registerA11yObserver();   // 用户在设置里开关无障碍时立即刷新
        ui.post(ticker);
    }

    @Override
    protected void onPause() {
        super.onPause();
        ui.removeCallbacks(ticker);
        if (a11yObs != null) {
            try { getContentResolver().unregisterContentObserver(a11yObs); } catch (Exception ignored) {}
            a11yObs = null;
        }
    }

    /** 监听系统无障碍开关变化 -> 立刻刷新状态 */
    private void registerA11yObserver() {
        if (a11yObs != null) return;
        a11yObs = new ContentObserver(ui) {
            @Override public void onChange(boolean selfChange) { refresh(); }
        };
        try {
            getContentResolver().registerContentObserver(
                    Settings.Secure.getUriFor(Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES),
                    true, a11yObs);
        } catch (Exception e) {
            a11yObs = null;
        }
    }

    private Button btn(String label, android.view.View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(label);
        b.setOnClickListener(l);
        return b;
    }

    private void refresh() {
        boolean on = false;
        try {
            AccessibilityManager am = (AccessibilityManager) getSystemService(Context.ACCESSIBILITY_SERVICE);
            List<AccessibilityServiceInfo> list =
                    am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK);
            for (AccessibilityServiceInfo i : list) {
                String id = i.getId() == null ? "" : i.getId();
                if (id.contains(WatchService.class.getName())) { on = true; break; }
            }
        } catch (Exception ignored) {}
        // 三态: 服务真连上 / 已授权但系统还没绑定 / 完全没开
        boolean alive = WatchService.alive;
        String txt;
        int color;
        if (alive) {
            txt = "✅ 无障碍已开启(服务已连接)";
            color = Color.parseColor("#0a7d28");
        } else if (on) {
            txt = "⏳ 已授权, 等系统绑定(通常几秒内; 无效就到设置里关掉本项再开)";
            color = Color.parseColor("#ef6c00");
        } else {
            txt = "❌ 无障碍未开启(去系统设置打开)";
            color = Color.parseColor("#c62828");
        }
        tvStatus.setText(txt);
        tvStatus.setTextColor(color);

        WatchConfig cfg = WatchConfig.get(this);
        tvApi.setText(apiText(cfg));

        long at = prefs().getLong("last_at", 0);
        String cls = prefs().getString("last_cls", "");
        String t = at > 0 ? new SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(new Date(at)) : "-";
        JSONObject lat = WatchBus.latest();
        tvLast.setText("最后事件: " + (lat != null && lat.has("ts") ? lat.toString() : "-")
                + "\n" + t + " " + (TextUtils.isEmpty(cls) ? "" : cls));
    }

    private String apiText(WatchConfig cfg) {
        return "本机 API: http://127.0.0.1:" + cfg.apiPort() + "/\n"
                + "token: " + cfg.token()
                + (cfg.pkgs().isEmpty()
                        ? "\n监听: (未选, 点「选择监听的应用」)"
                        : "\n监听(" + cfg.pkgs().size() + "): " + cfg.pkg()
                          + (cfg.act().isEmpty() ? "" : " / " + cfg.act()));
    }

    private SharedPreferences prefs() {
        return getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static String newToken() {
        byte[] b = new byte[16];
        new SecureRandom().nextBytes(b);
        StringBuilder sb = new StringBuilder();
        for (byte x : b) sb.append(String.format(Locale.US, "%02x", x));
        return sb.toString();
    }
}
