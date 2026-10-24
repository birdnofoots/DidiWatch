package com.birdnofoots.didiwatch;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.os.Bundle;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.accessibility.AccessibilityManager;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** 状态页: 无障碍是否开启 / 最后事件 / 发测试事件 / 是否附带节点树 */
public class MainActivity extends android.app.Activity {
    private static final String PREFS = "didiwatch";
    private TextView tvStatus, tvLast;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(32, 40, 32, 40);
        int p = (int) (16 * getResources().getDisplayMetrics().density);
        root.setPadding(p, p, p, p);

        tvStatus = new TextView(this);
        tvStatus.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        root.addView(tvStatus);

        tvLast = new TextView(this);
        tvLast.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        tvLast.setPadding(0, p, 0, p);
        root.addView(tvLast);

        CheckBox cb = new CheckBox(this);
        cb.setText("附带节点树(默认关, 打开后事件里会带完整节点)");
        cb.setChecked(prefs().getBoolean("include_tree", false));
        cb.setOnCheckedChangeListener((b, on) -> prefs().edit().putBoolean("include_tree", on).apply());
        root.addView(cb);

        Button open = new Button(this);
        open.setText("打开无障碍设置");
        open.setOnClickListener(v -> {
            try {
                startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
            } catch (Exception e) {
                tvLast.setText("打不开设置: " + e);
            }
        });
        root.addView(open);

        Button test = new Button(this);
        test.setText("发一个测试事件");
        test.setOnClickListener(v -> {
            JSONObject o = new JSONObject();
            try {
                o.put("kind", "test");
                o.put("ts", System.currentTimeMillis());
                o.put("origin", "apk");
                o.put("pkg", DidiWatchService.TARGET_PKG);
                o.put("cls", "manual_test");
            } catch (Exception ignored) {}
            EventPoster.postAsync(o);
            tvLast.setText("已发送测试事件 -> " + EventPoster.url());
        });
        root.addView(test);

        TextView tip = new TextView(this);
        tip.setText("\n用法:\n1. 打开无障碍设置, 启用「滴滴看单 · 无障碍」\n"
                + "2. 保持 Termux 里的脚本在跑(它监听 8765 端口)\n"
                + "3. 滴滴订单卡一弹出, 本 App 会立刻通知脚本\n\n"
                + "上报地址: " + EventPoster.url());
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
        refresh();
    }

    private void refresh() {
        boolean on = false;
        try {
            AccessibilityManager am = (AccessibilityManager) getSystemService(Context.ACCESSIBILITY_SERVICE);
            List<AccessibilityManager.AccessibilityServiceInfo> list =
                    am.getEnabledAccessibilityServiceList(AccessibilityManager.FEEDBACK_ALL_MASK);
            for (AccessibilityManager.AccessibilityServiceInfo i : list) {
                String id = i.getId() == null ? "" : i.getId().toString();
                if (id.contains(DidiWatchService.class.getName())) { on = true; break; }
            }
        } catch (Exception ignored) {}
        tvStatus.setText(on ? "✅ 无障碍已开启" : "❌ 无障碍未开启(去系统设置打开)");
        tvStatus.setTextColor(on ? Color.parseColor("#0a7d28") : Color.parseColor("#c62828"));

        long at = prefs().getLong("last_at", 0);
        String cls = prefs().getString("last_cls", "");
        String t = at > 0 ? new SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(new Date(at)) : "-";
        tvLast.setText("最后事件: " + t + "  " + (TextUtils.isEmpty(cls) ? "" : cls));
    }

    private SharedPreferences prefs() {
        return getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
