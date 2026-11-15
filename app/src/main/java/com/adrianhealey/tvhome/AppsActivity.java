package com.adrianhealey.tvhome;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.provider.Settings;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.GridLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Every app in an Apple TV-style grid, opened from the Apps button on the home screen. */
public class AppsActivity extends Activity implements Tiles.Listener {

    private Map<String, AppRepo.App> apps;
    private GridLayout grid;
    private ScrollView scroll;
    private String focusPkg;

    private int dp(float v) { return Tiles.dp(this, v); }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0xFF2C2C2E, 0xFF161618, 0xFF000000}));
        root.setPadding(dp(MainActivity.SIDE), dp(28), dp(MainActivity.SIDE), 0);
        root.setClipChildren(false);

        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = new TextView(this);
        title.setText("Apps");
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 34);
        title.setTextColor(Color.WHITE);
        title.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
        top.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        top.addView(Tiles.pill(this, "Options", v -> showOptions()));
        root.addView(top);

        scroll = new ScrollView(this);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setClipChildren(false);
        scroll.setClipToPadding(false);
        grid = new GridLayout(this);
        grid.setClipChildren(false);
        grid.setClipToPadding(false);
        grid.setPadding(dp(12), dp(26), dp(12), dp(60));
        float widthDp = getResources().getDisplayMetrics().widthPixels / getResources().getDisplayMetrics().density;
        int cols = (int) ((widthDp - 2 * MainActivity.SIDE - 24 + MainActivity.ICON_GAP)
                / (MainActivity.ICON_W + MainActivity.ICON_GAP));
        grid.setColumnCount(Math.max(3, cols));
        scroll.addView(grid);
        root.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

        setContentView(root);
        reload();
    }

    private void reload() {
        apps = AppRepo.load(this);
        render();
    }

    private void render() {
        View cur = getCurrentFocus();
        if (cur != null && cur.getTag() instanceof String) focusPkg = (String) cur.getTag();

        Set<String> hidden = Prefs.hidden(this);
        grid.removeAllViews();
        for (AppRepo.App a : apps.values()) {
            if (hidden.contains(a.pkg)) continue;
            View t = Tiles.appTile(this, a, dp(MainActivity.ICON_W), dp(MainActivity.ICON_H), this);
            GridLayout.LayoutParams lp = new GridLayout.LayoutParams();
            lp.setMargins(0, 0, dp(MainActivity.ICON_GAP), dp(14));
            grid.addView(t, lp);
        }
        grid.post(() -> {
            if (focusPkg != null) {
                for (int i = 0; i < grid.getChildCount(); i++) {
                    View v = grid.getChildAt(i);
                    if (focusPkg.equals(v.getTag())) { v.requestFocus(); return; }
                }
            }
            if (grid.getChildCount() > 0) grid.getChildAt(0).requestFocus();
        });
    }

    // ------------------------------------------------------------ tile events

    @Override public void onFocus(AppRepo.App a, View tile) { }

    @Override public void onOpen(AppRepo.App a, View tile) { AppRepo.launch(this, a); }

    @Override public void onHold(AppRepo.App a, View tile) { appMenu(a); }

    @Override public void onMenu(AppRepo.App a, View tile) { appMenu(a); }

    private void appMenu(final AppRepo.App a) {
        final List<String> home = AppRepo.homeRow(this, apps);
        final boolean onHome = home.contains(a.pkg);
        String[] items = {
                onHome ? "Remove from Home" : "Add to Home",
                "Hide",
                "App info",
        };
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle(a.label)
                .setItems(items, (d, which) -> {
                    if (which == 0) {
                        if (onHome) home.remove(a.pkg); else home.add(a.pkg);
                        Prefs.setFavs(this, home);
                        Toast.makeText(this, onHome ? "Removed from Home" : "Added to Home",
                                Toast.LENGTH_SHORT).show();
                    } else if (which == 1) {
                        Set<String> h = Prefs.hidden(this);
                        h.add(a.pkg);
                        Prefs.setHidden(this, h);
                        focusPkg = null;
                        render();
                    } else {
                        AppRepo.appInfo(this, a);
                    }
                })
                .show();
    }

    // --------------------------------------------------------------- options

    private boolean watcherEnabledInSystem() {
        String s = Settings.Secure.getString(getContentResolver(),
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        return s != null && s.toLowerCase(Locale.ROOT).contains(getPackageName().toLowerCase(Locale.ROOT));
    }

    private void showOptions() {
        final boolean on = Prefs.watcherOn(this);
        final boolean sys = watcherEnabledInSystem();
        String[] items = {
                "Home button opens TV Home: " + (on ? "On" : "Off") + (sys ? "" : " (service not enabled)"),
                "Show hidden apps",
                "Reset Home row",
                "Open Amazon home screen",
                "About (version " + versionName() + ")",
        };
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("TV Home")
                .setItems(items, (d, which) -> {
                    switch (which) {
                        case 0:
                            Prefs.setWatcherOn(this, !on);
                            if (!sys) {
                                try {
                                    startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                                } catch (Exception ignored) { }
                            }
                            break;
                        case 1:
                            Prefs.setHidden(this, new HashSet<String>());
                            render();
                            break;
                        case 2:
                            Prefs.setFavs(this, new ArrayList<>(Arrays.asList(Prefs.DEFAULT_FAVS)));
                            Toast.makeText(this, "Home row reset", Toast.LENGTH_SHORT).show();
                            break;
                        case 3:
                            openAmazonHome();
                            break;
                        default:
                            break;
                    }
                })
                .show();
    }

    private void openAmazonHome() {
        Prefs.pauseWatcher(this, 60_000);   // let Amazon's home screen show for a minute
        Intent i = getPackageManager().getLaunchIntentForPackage("com.amazon.tv.launcher");
        if (i == null) i = getPackageManager().getLeanbackLaunchIntentForPackage("com.amazon.tv.launcher");
        if (i == null) {
            Prefs.pauseWatcher(this, 0);
            Toast.makeText(this, "Amazon home screen not found", Toast.LENGTH_SHORT).show();
            return;
        }
        startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    }

    private String versionName() {
        try { return getPackageManager().getPackageInfo(getPackageName(), 0).versionName; }
        catch (Exception e) { return "?"; }
    }
}
