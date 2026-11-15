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
        sp(c).edit().putString("favs", TextUtils.join(",", favs)).apply();
    }

    static Set<String> hidden(Context c) {
        return new HashSet<>(sp(c).getStringSet("hidden", new HashSet<String>()));
    }

    static void setHidden(Context c, Set<String> hidden) {
        sp(c).edit().putStringSet("hidden", new HashSet<>(hidden)).apply();
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
