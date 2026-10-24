package com.birdnofoots.eventviewer;

import android.app.Activity;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.birdnofoots.eventviewer.lib.WatchConfig;

import java.util.Collections;
import java.util.List;

/**
 * 选择要监听哪些已安装应用。点一下即切换(高亮 = 监听中)。
 * 需要 QUERY_ALL_PACKAGES 才能列出全部应用(普通权限, 已在 manifest 声明)。
 */
public class AppPickerActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        final WatchConfig cfg = WatchConfig.get(this);
        final PackageManager pm = getPackageManager();

        List<ApplicationInfo> apps = pm.getInstalledApplications(0);
        Collections.sort(apps, (a, b) ->
                pm.getApplicationLabel(a).toString()
                        .compareToIgnoreCase(pm.getApplicationLabel(b).toString()));

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int p = dp(12);
        root.setPadding(p, p, p, p);

        TextView tip = new TextView(this);
        tip.setText("点一下加入 / 移出监听列表（高亮 = 监听中）。"
                + "\n只监听窗口切换事件，不读取任何业务数据。");
        tip.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        tip.setPadding(0, 0, 0, p);
        root.addView(tip);

        for (final ApplicationInfo ai : apps) {
            final String pkg = ai.packageName;
            final String label = pm.getApplicationLabel(ai).toString();
            final TextView row = new TextView(this);
            draw(row, cfg.pkgs().contains(pkg), label, pkg);
            row.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    if (cfg.pkgs().contains(pkg)) cfg.removePkg(pkg);
                    else cfg.addPkg(pkg);
                    draw(row, cfg.pkgs().contains(pkg), label, pkg);
                }
            });
            root.addView(row);
        }

        ScrollView sv = new ScrollView(this);
        sv.addView(root);
        setContentView(sv);
    }

    private void draw(TextView row, boolean on, String label, String pkg) {
        row.setText((on ? "●  " : "○  ") + label + "\n     " + pkg);
        row.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        row.setTypeface(Typeface.MONOSPACE);
        row.setPadding(dp(12), dp(10), dp(12), dp(10));
        row.setBackgroundColor(on ? Color.parseColor("#143d14") : Color.TRANSPARENT);
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }
}
