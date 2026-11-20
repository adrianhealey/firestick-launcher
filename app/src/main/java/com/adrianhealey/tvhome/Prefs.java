package com.adrianhealey.tvhome;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Favourites order, hidden apps and the Home-button setting. */
final class Prefs {
    private Prefs() { }

    // Apps pinned to the top row the first time the launcher runs (if installed).
    static final String[] DEFAULT_FAVS = {
            "tvhome.builtin.cameras",       // built-in Cameras (HA cameras web app)
            "com.edde746.plezy",            // Plezy (Plex)
            "org.smarttube.stable",         // SmartTube
            "com.amazon.firetv.youtube",
            "uk.co.bbc.iplayer",
            "air.ITVMobilePlayer",          // ITVX
            "com.channel4.ondemand",
            "ar.tvplayer.tv",               // TiviMate
            "com.disney.disneyplus",
            "com.amazon.avod",              // Prime Video
            "org.xbmc.kodi",
            "com.spotify.tv.android",
            "uk.co.uktv.dave",              // U
    };

    private static SharedPreferences sp(Context c) {
        return c.getSharedPreferences("tvhome", Context.MODE_PRIVATE);
    }

    static boolean hasFavs(Context c) { return sp(c).contains("favs"); }

    static List<String> favs(Context c) {
        String s = sp(c).getString("favs", null);
        if (s == null) return new ArrayList<>(Arrays.asList(DEFAULT_FAVS));
        if (s.isEmpty()) return new ArrayList<>();
        return new ArrayList<>(Arrays.asList(s.split(",")));
    }

    static void setFavs(Context c, List<String> favs) {
        sp(c).edit().putString("favs", TextUtils.join(",", favs)).putBoolean("changed", true).apply();
    }

    /** One time only, add the built-in Cameras app to the Home row (respects a later removal). */
    static void seedCameras(Context c, String pkg) {
        SharedPreferences sp = sp(c);
        if (sp.getBoolean("cam_seeded", false)) return;
        List<String> f = favs(c);
        if (!f.contains(pkg)) { f.add(0, pkg); setFavs(c, f); }
        sp.edit().putBoolean("cam_seeded", true).apply();
    }

    /** One time only, add a built-in app to the Home row (respects a later removal). */
    static void seedApp(Context c, String pkg, String flag) {
        SharedPreferences sp = sp(c);
        if (sp.getBoolean(flag, false)) return;
        List<String> f = favs(c);
        if (!f.contains(pkg)) { f.add(0, pkg); setFavs(c, f); }
        sp.edit().putBoolean(flag, true).apply();
    }

    /** True once after the home row or hidden list changed (then cleared). */
    static boolean consumeChanged(Context c) {
        boolean ch = sp(c).getBoolean("changed", false);
        if (ch) sp(c).edit().putBoolean("changed", false).apply();
        return ch;
    }

    // Amazon apps that stay visible; every other com.amazon.* app starts hidden.
    static final String[] AMAZON_KEEP = {
            "com.amazon.avod",              // Prime Video
            "com.amazon.firetv.youtube",
            "com.amazon.venezia",           // Appstore
            "com.amazon.cloud9",            // Silk browser
            "com.amazon.tv.settings.v2",
    };

    static boolean hasHidden(Context c) { return sp(c).contains("hidden"); }

    static Set<String> hidden(Context c) {
        return new HashSet<>(sp(c).getStringSet("hidden", new HashSet<String>()));
    }

    static void setHidden(Context c, Set<String> hidden) {
        sp(c).edit().putStringSet("hidden", new HashSet<>(hidden)).putBoolean("changed", true).apply();
    }

    static boolean watcherOn(Context c) { return sp(c).getBoolean("watcher", true); }

    static void setWatcherOn(Context c, boolean on) { sp(c).edit().putBoolean("watcher", on).apply(); }

    /** Let Amazon's home screen show for a while (e.g. "Open Amazon home screen"). */
    static void pauseWatcher(Context c, long ms) {
        sp(c).edit().putLong("pauseUntil", android.os.SystemClock.elapsedRealtime() + ms).apply();
    }

    static boolean watcherPaused(Context c) {
        return android.os.SystemClock.elapsedRealtime() < sp(c).getLong("pauseUntil", 0);
    }
}
