package com.adrianhealey.tvhome;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.TextView;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.NumberFormat;

/**
 * A small top-bar pill showing today's step count from Home Assistant.
 * Reads the first step sensor that has a real number, over the LAN, using
 * the same access token the Cameras app stores on this device.
 */
final class StepsBadge extends FrameLayout {

    static final String BASE = "http://192.168.0.219:8123";
    // Primary then fallback step sensors (first with a real number wins).
    // Only the Pixel Watch daily steps (Adrian).
    static final String[] ENTITIES = {
            "sensor.google_pixel_watch_daily_steps"
    };
    private static final long REFRESH_MS = 5 * 60 * 1000L;
    private static final String TAG = "tvhome-steps";

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final TextView label;

    StepsBadge(Context c) {
        super(c);
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.RECTANGLE);
        bg.setCornerRadius(dp(26));
        bg.setColor(0x40FFFFFF);
        if (Theme.GTA) { bg.setColor(0x33104A4A); bg.setStroke(dp(1), 0xAA1BE7E7); }  // cyan neon edge
        setBackground(bg);
        setPadding(dp(16), 0, dp(16), 0);
        setElevation(dp(2));

        label = new TextView(c);
        label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        label.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        label.setTextColor(Color.WHITE);
        label.setText("\uD83D\uDC63 \u2014");
        addView(label, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));

        String cached = prefs().getString("txt", null);
        if (cached != null) label.setText(cached);
    }

    @Override protected void onAttachedToWindow() { super.onAttachedToWindow(); ui.post(refresh); }
    @Override protected void onDetachedFromWindow() { ui.removeCallbacks(refresh); super.onDetachedFromWindow(); }

    void refreshIfStale() {
        if (System.currentTimeMillis() - prefs().getLong("at", 0) > REFRESH_MS) {
            ui.removeCallbacks(refresh);
            ui.post(refresh);
        }
    }

    private final Runnable refresh = new Runnable() {
        @Override public void run() {
            final String token = token();
            new Thread(() -> {
                final String txt = fetch(token);
                if (txt != null) {
                    ui.post(() -> {
                        label.setText(txt);
                        prefs().edit().putString("txt", txt).putLong("at", System.currentTimeMillis()).apply();
                    });
                }
            }).start();
            ui.postDelayed(this, REFRESH_MS);
        }
    };

    private SharedPreferences prefs() { return getContext().getSharedPreferences("steps", Context.MODE_PRIVATE); }
    private String token() { return getContext().getSharedPreferences("cameras", Context.MODE_PRIVATE).getString("token", ""); }

    private static String fetch(String token) {
        if (token == null || token.isEmpty()) return null;
        for (String ent : ENTITIES) {
            HttpURLConnection con = null;
            try {
                con = (HttpURLConnection) new URL(BASE + "/api/states/" + ent).openConnection();
                con.setConnectTimeout(8000);
                con.setReadTimeout(8000);
                con.setRequestProperty("Authorization", "Bearer " + token);
                con.setRequestProperty("Accept", "application/json");
                if (con.getResponseCode() != 200) { con.disconnect(); continue; }
                StringBuilder sb = new StringBuilder();
                try (BufferedReader r = new BufferedReader(new InputStreamReader(con.getInputStream(), "UTF-8"))) {
                    String line;
                    while ((line = r.readLine()) != null) sb.append(line);
                }
                String st = new JSONObject(sb.toString()).optString("state", "");
                if (st.isEmpty() || st.equals("unknown") || st.equals("unavailable")) continue;
                double v;
                try { v = Double.parseDouble(st); } catch (NumberFormatException e) { continue; }
                return "\uD83D\uDC63 " + NumberFormat.getIntegerInstance().format(Math.round(v));
            } catch (Exception e) {
                Log.w(TAG, "steps fetch failed: " + e);
            } finally {
                if (con != null) con.disconnect();
            }
        }
        return null;
    }

    private int dp(float v) { return Tiles.dp(getContext(), v); }
}
