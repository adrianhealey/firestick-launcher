package com.adrianhealey.tvhome;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.provider.Settings;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Finds launchable apps and opens them. */
final class AppRepo {
    private AppRepo() { }

    static final class App {
        String pkg;
        String label;
        Intent launch;
        Drawable art;
        boolean isBanner;
        int tint;          // average colour of the icon, used behind icon-only apps
    }

    /** All launchable apps, sorted by name (this app excluded). */
    static Map<String, App> load(Context c) {
        PackageManager pm = c.getPackageManager();
        Map<String, App> found = new HashMap<>();
        addFrom(pm, Intent.CATEGORY_LEANBACK_LAUNCHER, found);   // TV banners first
        addFrom(pm, Intent.CATEGORY_LAUNCHER, found);
        found.remove(c.getPackageName());

        List<App> list = new ArrayList<>(found.values());
        Collections.sort(list, (a, b) -> a.label.compareToIgnoreCase(b.label));
        Map<String, App> apps = new LinkedHashMap<>();
        for (App a : list) apps.put(a.pkg, a);

        // First run: hide Amazon's own extras (shopping, Luna, miniTV...).
        if (!Prefs.hasHidden(c)) {
            Set<String> h = new HashSet<>();
            List<String> keep = Arrays.asList(Prefs.AMAZON_KEEP);
            for (String p : apps.keySet()) if (p.startsWith("com.amazon.") && !keep.contains(p)) h.add(p);
            Prefs.setHidden(c, h);
        }
        return apps;
    }

    private static void addFrom(PackageManager pm, String category, Map<String, App> out) {
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
            a.tint = Tiles.averageColour(a.art);
            out.put(pkg, a);
        }
    }

    static void launch(Activity act, App a) {
        try {
            act.startActivity(a.launch);
            return;
        } catch (Exception ignored) { }
        PackageManager pm = act.getPackageManager();
        Intent i = pm.getLaunchIntentForPackage(a.pkg);
        if (i == null) i = pm.getLeanbackLaunchIntentForPackage(a.pkg);
        if (i != null) {
            try { act.startActivity(i); return; } catch (Exception ignored) { }
        }
        Toast.makeText(act, "Couldn't open " + a.label, Toast.LENGTH_SHORT).show();
    }

    static void appInfo(Activity act, App a) {
        try {
            act.startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:" + a.pkg)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (Exception e) {
            Toast.makeText(act, "App info not available", Toast.LENGTH_SHORT).show();
        }
    }

    static void openSystemSettings(Activity act) {
        Intent[] tries = {
                new Intent(Settings.ACTION_SETTINGS),
                new Intent().setComponent(new ComponentName("com.amazon.tv.settings.v2",
                        "com.amazon.tv.settings.v2.tv.MainSettingsActivity")),
        };
        for (Intent i : tries) {
            try { act.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); return; }
            catch (Exception ignored) { }
        }
        Toast.makeText(act, "Settings not found", Toast.LENGTH_SHORT).show();
    }

    /** Home-row apps that are installed, in order. */
    static List<String> homeRow(Context c, Map<String, App> apps) {
        List<String> out = new ArrayList<>();
        for (String p : Prefs.favs(c)) if (apps.containsKey(p) && !out.contains(p)) out.add(p);
        return out;
    }
}
