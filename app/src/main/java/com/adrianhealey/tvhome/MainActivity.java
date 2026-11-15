package com.adrianhealey.tvhome;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.GridLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextClock;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public class MainActivity extends Activity {

    private static final int ACCENT = 0xFFFFB347;
    private static final int TILE_BG = 0xFF1A2440;
    private static final int TEXT_DIM = 0xFF9AA4BF;
    private static final int SIDE_PAD = 56;     // dp
    private static final int SMALL_W = 150, SMALL_H = 84, SMALL_GAP = 18;   // dp

    /** One launchable app. */
    static final class App {
        String pkg;
        String label;
        Intent launch;
        Drawable art;
        boolean isBanner;
    }

    private final Map<String, App> apps = new LinkedHashMap<>();
    private final Handler ui = new Handler(Looper.getMainLooper());

    private LinearLayout favRow;
    private GridLayout allGrid;
    private TextView favEmpty;
    private ScrollView scroller;
    private String focusPkg;           // tile to re-focus after a rebuild
    private boolean focusInFavs = true;

    private final BroadcastReceiver packagesChanged = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) { reloadSoon(); }
    };

    // ------------------------------------------------------------------ setup

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(buildUi());

        IntentFilter f = new IntentFilter();
        f.addAction(Intent.ACTION_PACKAGE_ADDED);
        f.addAction(Intent.ACTION_PACKAGE_REMOVED);
        f.addAction(Intent.ACTION_PACKAGE_CHANGED);
        f.addAction(Intent.ACTION_PACKAGE_REPLACED);
        f.addDataScheme("package");
        registerReceiver(packagesChanged, f);

        loadApps();
        render();
    }

    @Override
    protected void onDestroy() {
        try { unregisterReceiver(packagesChanged); } catch (Exception ignored) { }
        super.onDestroy();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        // Home pressed while already here: jump back to the first favourite.
        scroller.smoothScrollTo(0, 0);
        focusFirst();
    }

    @Override
    public void onBackPressed() {
        // The home screen has nowhere to go back to.
    }

    private int dp(float v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                getResources().getDisplayMetrics()));
    }

    private TextView text(String s, float sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        return t;
    }

    private View buildUi() {
        scroller = new ScrollView(this);
        scroller.setFillViewport(true);
        scroller.setVerticalScrollBarEnabled(false);
        scroller.setSmoothScrollingEnabled(true);

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(SIDE_PAD), dp(32), dp(SIDE_PAD), dp(48));
        col.setClipChildren(false);
        col.setClipToPadding(false);
        scroller.addView(col);

        // header: clock + date on the left, buttons on the right
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);

        LinearLayout clockCol = new LinearLayout(this);
        clockCol.setOrientation(LinearLayout.VERTICAL);
        TextClock clock = new TextClock(this);
        clock.setFormat24Hour("HH:mm");
        clock.setFormat12Hour("HH:mm");
        clock.setTextSize(TypedValue.COMPLEX_UNIT_SP, 44);
        clock.setTextColor(Color.WHITE);
        clock.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
        TextClock date = new TextClock(this);
        date.setFormat24Hour("EEEE d MMMM");
        date.setFormat12Hour("EEEE d MMMM");
        date.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        date.setTextColor(TEXT_DIM);
        clockCol.addView(clock);
        clockCol.addView(date);
        header.addView(clockCol, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));

        header.addView(pill("Settings", new View.OnClickListener() {
            @Override public void onClick(View v) { openSystemSettings(); }
        }));
        View gap = new View(this);
        header.addView(gap, new LinearLayout.LayoutParams(dp(12), 1));
        header.addView(pill("Launcher", new View.OnClickListener() {
            @Override public void onClick(View v) { showLauncherMenu(); }
        }));
        col.addView(header);

        // favourites
        col.addView(sectionTitle("Favourites"));
        HorizontalScrollView hs = new HorizontalScrollView(this);
        hs.setHorizontalScrollBarEnabled(false);
        hs.setClipChildren(false);
        hs.setClipToPadding(false);
        hs.setPadding(dp(8), dp(10), dp(8), dp(10));
        favRow = new LinearLayout(this);
        favRow.setOrientation(LinearLayout.HORIZONTAL);
        favRow.setClipChildren(false);
        hs.addView(favRow);
        col.addView(hs);
        favEmpty = text("Press and hold Select (or the ☰ button) on any app below to add it here.",
                15, TEXT_DIM, false);
        favEmpty.setPadding(dp(8), 0, 0, dp(8));
        col.addView(favEmpty);

        // all apps
        col.addView(sectionTitle("All apps"));
        allGrid = new GridLayout(this);
        float widthDp = getResources().getDisplayMetrics().widthPixels / getResources().getDisplayMetrics().density;
        int cols = (int) ((widthDp - 2 * SIDE_PAD - 16 + SMALL_GAP) / (SMALL_W + SMALL_GAP));
        allGrid.setColumnCount(Math.max(3, cols));
        allGrid.setClipChildren(false);
        allGrid.setPadding(dp(8), dp(10), dp(8), dp(10));
        col.addView(allGrid);

        return scroller;
    }

    private TextView sectionTitle(String s) {
        TextView t = text(s, 20, Color.WHITE, true);
        t.setPadding(dp(8), dp(28), 0, dp(4));
        return t;
    }

    private TextView pill(String label, View.OnClickListener click) {
        TextView t = text(label, 16, Color.WHITE, true);
        t.setPadding(dp(22), dp(10), dp(22), dp(10));
        t.setFocusable(true);
        t.setClickable(true);
        t.setBackground(focusBackground(dp(24), 0x33FFFFFF));
        t.setOnClickListener(click);
        t.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override public void onFocusChange(View v, boolean has) {
                ((TextView) v).setTextColor(has ? Color.BLACK : Color.WHITE);
            }
        });
        return t;
    }

    /** Rounded background that turns accent-coloured and outlined when focused. */
    private StateListDrawable focusBackground(int radius, int normal) {
        GradientDrawable on = new GradientDrawable();
        on.setCornerRadius(radius);
        on.setColor(ACCENT);
        GradientDrawable off = new GradientDrawable();
        off.setCornerRadius(radius);
        off.setColor(normal);
        StateListDrawable s = new StateListDrawable();
        s.addState(new int[]{android.R.attr.state_focused}, on);
        s.addState(new int[]{}, off);
        return s;
    }

    private StateListDrawable tileBackground(int radius) {
        GradientDrawable on = new GradientDrawable();
        on.setCornerRadius(radius);
        on.setColor(TILE_BG);
        on.setStroke(dp(3), ACCENT);
        GradientDrawable off = new GradientDrawable();
        off.setCornerRadius(radius);
        off.setColor(TILE_BG);
        StateListDrawable s = new StateListDrawable();
        s.addState(new int[]{android.R.attr.state_focused}, on);
        s.addState(new int[]{}, off);
        return s;
    }

    // ------------------------------------------------------------- app list

    private void loadApps() {
        PackageManager pm = getPackageManager();
        Map<String, App> found = new HashMap<>();
        // TV (leanback) entries first, so their banners win over phone icons.
        addFrom(pm, Intent.CATEGORY_LEANBACK_LAUNCHER, found);
        addFrom(pm, Intent.CATEGORY_LAUNCHER, found);
        found.remove(getPackageName());

        List<App> list = new ArrayList<>(found.values());
        Collections.sort(list, new Comparator<App>() {
            @Override public int compare(App a, App b) {
                return a.label.compareToIgnoreCase(b.label);
            }
        });
        apps.clear();
        for (App a : list) apps.put(a.pkg, a);

        // First run: hide Amazon's own extras (shopping, Luna, miniTV...).
        if (!Prefs.hasHidden(this)) {
            java.util.Set<String> h = new java.util.HashSet<>();
            java.util.List<String> keep = java.util.Arrays.asList(Prefs.AMAZON_KEEP);
            for (String p : apps.keySet()) if (p.startsWith("com.amazon.") && !keep.contains(p)) h.add(p);
            Prefs.setHidden(this, h);
        }
    }

    private void addFrom(PackageManager pm, String category, Map<String, App> out) {
        Intent q = new Intent(Intent.ACTION_MAIN).addCategory(category);
        List<ResolveInfo> res;
        try { res = pm.queryIntentActivities(q, 0); } catch (Exception e) { return; }
        for (ResolveInfo ri : res) {
            if (ri.activityInfo == null) continue;
            String pkg = ri.activityInfo.packageName;
            if (out.containsKey(pkg)) continue;
            App a = new App();
            a.pkg = pkg;
            CharSequence l = ri.loadLabel(pm);
            a.label = l == null ? pkg : l.toString();
            a.launch = new Intent(Intent.ACTION_MAIN)
                    .addCategory(category)
                    .setComponent(new ComponentName(pkg, ri.activityInfo.name))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
            Drawable banner = null;
            try { banner = ri.activityInfo.loadBanner(pm); } catch (Exception ignored) { }
            if (banner != null) {
                a.art = banner;
                a.isBanner = true;
            } else {
                try { a.art = ri.loadIcon(pm); } catch (Exception ignored) { }
            }
            out.put(pkg, a);
        }
    }

    private void reloadSoon() {
        ui.removeCallbacks(reloadRunnable);
        ui.postDelayed(reloadRunnable, 500);
    }

    private final Runnable reloadRunnable = new Runnable() {
        @Override public void run() { loadApps(); render(); }
    };

    // ---------------------------------------------------------------- render

    private void render() {
        View cur = getCurrentFocus();
        if (cur != null && cur.getTag() instanceof String) {
            focusPkg = (String) cur.getTag();
            focusInFavs = cur.getParent() == favRow;
        }

        List<String> favs = new ArrayList<>();
        for (String p : Prefs.favs(this)) if (apps.containsKey(p) && !favs.contains(p)) favs.add(p);
        Set<String> hidden = Prefs.hidden(this);

        favRow.removeAllViews();
        for (String p : favs) favRow.addView(tile(apps.get(p), true));
        favEmpty.setVisibility(favs.isEmpty() ? View.VISIBLE : View.GONE);

        allGrid.removeAllViews();
        for (App a : apps.values()) {
            if (hidden.contains(a.pkg)) continue;
            allGrid.addView(tile(a, false));
        }

        ui.post(new Runnable() {
            @Override public void run() { restoreFocus(); }
        });
    }

    private void restoreFocus() {
        if (focusPkg != null) {
            ViewGroup g = focusInFavs ? favRow : allGrid;
            for (int i = 0; i < g.getChildCount(); i++) {
                View v = g.getChildAt(i);
                if (focusPkg.equals(v.getTag())) { v.requestFocus(); return; }
            }
        }
        focusFirst();
    }

    private void focusFirst() {
        if (favRow.getChildCount() > 0) favRow.getChildAt(0).requestFocus();
        else if (allGrid.getChildCount() > 0) allGrid.getChildAt(0).requestFocus();
    }

    private View tile(final App a, final boolean big) {
        final int w = big ? dp(240) : dp(SMALL_W);
        final int h = big ? dp(135) : dp(SMALL_H);

        final FrameLayout f = new FrameLayout(this);
        f.setTag(a.pkg);
        f.setFocusable(true);
        f.setClickable(true);
        f.setBackground(tileBackground(dp(10)));
        f.setClipToOutline(true);
        int pad = dp(3);
        f.setPadding(pad, pad, pad, pad);

        ImageView img = new ImageView(this);
        img.setImageDrawable(a.art);
        if (a.isBanner) {
            img.setScaleType(ImageView.ScaleType.FIT_CENTER);
            f.addView(img, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT));
        } else {
            // phone-style app: icon with its name underneath
            img.setScaleType(ImageView.ScaleType.FIT_CENTER);
            int s = big ? dp(64) : dp(40);
            FrameLayout.LayoutParams ip = new FrameLayout.LayoutParams(s, s, Gravity.CENTER_HORIZONTAL | Gravity.TOP);
            ip.topMargin = big ? dp(18) : dp(10);
            f.addView(img, ip);
            TextView name = text(a.label, big ? 15 : 12, Color.WHITE, false);
            name.setSingleLine(true);
            name.setEllipsize(TextUtils.TruncateAt.END);
            name.setGravity(Gravity.CENTER);
            FrameLayout.LayoutParams np = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM);
            np.leftMargin = np.rightMargin = dp(6);
            np.bottomMargin = big ? dp(14) : dp(8);
            f.addView(name, np);
        }

        ViewGroup.MarginLayoutParams lp;
        if (big) {
            LinearLayout.LayoutParams l = new LinearLayout.LayoutParams(w, h);
            l.rightMargin = dp(20);
            lp = l;
        } else {
            GridLayout.LayoutParams g = new GridLayout.LayoutParams();
            g.width = w;
            g.height = h;
            g.setMargins(0, 0, dp(SMALL_GAP), dp(SMALL_GAP));
            lp = g;
        }
        f.setLayoutParams(lp);

        f.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override public void onFocusChange(View v, boolean has) {
                v.animate().scaleX(has ? 1.1f : 1f).scaleY(has ? 1.1f : 1f)
                        .translationZ(has ? dp(8) : 0).setDuration(120).start();
            }
        });
        f.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { launch(a); }
        });
        f.setOnLongClickListener(new View.OnLongClickListener() {
            @Override public boolean onLongClick(View v) { showAppMenu(a, big); return true; }
        });
        f.setOnKeyListener(new View.OnKeyListener() {
            @Override public boolean onKey(View v, int code, KeyEvent e) {
                if (code == KeyEvent.KEYCODE_MENU && e.getAction() == KeyEvent.ACTION_UP) {
                    showAppMenu(a, big);
                    return true;
                }
                return code == KeyEvent.KEYCODE_MENU;
            }
        });
        return f;
    }

    // --------------------------------------------------------------- actions

    private void launch(App a) {
        try {
            startActivity(a.launch);
        } catch (Exception e) {
            Intent i = getPackageManager().getLaunchIntentForPackage(a.pkg);
            if (i == null) i = getPackageManager().getLeanbackLaunchIntentForPackage(a.pkg);
            if (i != null) {
                try { startActivity(i); return; } catch (Exception ignored) { }
            }
            Toast.makeText(this, "Couldn't open " + a.label, Toast.LENGTH_SHORT).show();
        }
    }

    private void openSystemSettings() {
        Intent[] tries = {
                new Intent(Settings.ACTION_SETTINGS),
                new Intent().setComponent(new ComponentName("com.amazon.tv.settings.v2",
                        "com.amazon.tv.settings.v2.tv.MainSettingsActivity")),
        };
        for (Intent i : tries) {
            try { startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); return; }
            catch (Exception ignored) { }
        }
        Toast.makeText(this, "Settings not found", Toast.LENGTH_SHORT).show();
    }

    private void showAppMenu(final App a, final boolean inFavs) {
        final List<String> favs = Prefs.favs(this);
        favs.retainAll(apps.keySet());
        final int idx = favs.indexOf(a.pkg);
        final List<String> labels = new ArrayList<>();
        final List<Runnable> actions = new ArrayList<>();

        if (idx >= 0) {
            if (idx > 0) {
                labels.add("Move left");
                actions.add(new Runnable() { @Override public void run() {
                    Collections.swap(favs, idx, idx - 1); focusPkg = a.pkg; focusInFavs = true;
                    Prefs.setFavs(MainActivity.this, favs); render(); } });
            }
            if (idx < favs.size() - 1) {
                labels.add("Move right");
                actions.add(new Runnable() { @Override public void run() {
                    Collections.swap(favs, idx, idx + 1); focusPkg = a.pkg; focusInFavs = true;
                    Prefs.setFavs(MainActivity.this, favs); render(); } });
            }
            labels.add("Remove from favourites");
            actions.add(new Runnable() { @Override public void run() {
                favs.remove(a.pkg); Prefs.setFavs(MainActivity.this, favs); render(); } });
        } else {
            labels.add("Add to favourites");
            actions.add(new Runnable() { @Override public void run() {
                favs.add(a.pkg); Prefs.setFavs(MainActivity.this, favs); render(); } });
        }
        if (!inFavs) {
            labels.add("Hide from All apps");
            actions.add(new Runnable() { @Override public void run() {
                Set<String> h = Prefs.hidden(MainActivity.this); h.add(a.pkg);
                Prefs.setHidden(MainActivity.this, h); render(); } });
        }
        labels.add("App info");
        actions.add(new Runnable() { @Override public void run() {
            try {
                startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.parse("package:" + a.pkg)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            } catch (Exception e) {
                Toast.makeText(MainActivity.this, "App info not available", Toast.LENGTH_SHORT).show();
            } } });

        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle(a.label)
                .setItems(labels.toArray(new String[0]), new android.content.DialogInterface.OnClickListener() {
                    @Override public void onClick(android.content.DialogInterface d, int which) {
                        actions.get(which).run();
                    }
                })
                .show();
    }

    private boolean watcherEnabledInSystem() {
        String s = Settings.Secure.getString(getContentResolver(),
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        return s != null && s.toLowerCase(Locale.ROOT).contains(getPackageName().toLowerCase(Locale.ROOT));
    }

    private void showLauncherMenu() {
        final boolean on = Prefs.watcherOn(this);
        final boolean sys = watcherEnabledInSystem();
        String homeLine = "Home button opens TV Home: " + (on ? "On" : "Off")
                + (sys ? "" : " (service not enabled)");
        String[] items = {
                homeLine,
                "Show hidden apps again",
                "Reset favourites",
                "Open Amazon home screen",
                "About (version " + versionName() + ")",
        };
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("TV Home")
                .setItems(items, new android.content.DialogInterface.OnClickListener() {
                    @Override public void onClick(android.content.DialogInterface d, int which) {
                        switch (which) {
                            case 0:
                                Prefs.setWatcherOn(MainActivity.this, !on);
                                if (!sys) openAccessibilitySettings();
                                break;
                            case 1:
                                Prefs.setHidden(MainActivity.this, new java.util.HashSet<String>());
                                render();
                                break;
                            case 2:
                                List<String> def = new ArrayList<>();
                                for (String p : Prefs.DEFAULT_FAVS) def.add(p);
                                Prefs.setFavs(MainActivity.this, def);
                                render();
                                break;
                            case 3:
                                openAmazonHome();
                                break;
                            default:
                                break;
                        }
                    }
                })
                .show();
    }

    private void openAccessibilitySettings() {
        try {
            startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (Exception ignored) { }
    }

    private void openAmazonHome() {
        // Pause the takeover for a minute so Amazon's home can actually show.
        Prefs.pauseWatcher(this, 60_000);
        Intent i = getPackageManager().getLaunchIntentForPackage("com.amazon.tv.launcher");
        if (i == null) i = getPackageManager().getLeanbackLaunchIntentForPackage("com.amazon.tv.launcher");
        if (i == null) {
            Toast.makeText(this, "Amazon home screen not found", Toast.LENGTH_SHORT).show();
            Prefs.pauseWatcher(this, 0);
            return;
        }
        startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    }

    private String versionName() {
        try { return getPackageManager().getPackageInfo(getPackageName(), 0).versionName; }
        catch (Exception e) { return "?"; }
    }
}
