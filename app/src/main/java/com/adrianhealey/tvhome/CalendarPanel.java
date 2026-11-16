package com.adrianhealey.tvhome;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.Log;
import android.util.TypedValue;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * The next 7 days of events from the Home Assistant family calendar. Uses the
 * long-lived access token the Cameras app already stores on this device, calling
 * Home Assistant's REST API over the public Tailscale funnel.
 */
final class CalendarPanel extends LinearLayout {

    static final String BASE = "http://192.168.0.219:8123";
    static final String ENTITY = "calendar.family_2_2";
    private static final int DAYS = 7;
    private static final int MAX_ROWS = 3;
    private static final long REFRESH_MS = 30 * 60 * 1000L;
    private static final String TAG = "tvhome-cal";

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final LinearLayout list;

    CalendarPanel(Context c) {
        super(c);
        setOrientation(VERTICAL);
        setPadding(dp(14), dp(8), dp(14), dp(8));
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(16));
        bg.setColor(0x59101826);
        bg.setStroke(dp(1), 0x26FFFFFF);
        setBackground(bg);

        TextView label = text(11, 0x99FFFFFF, "sans-serif-medium");
        label.setText("📅  NEXT 7 DAYS");
        label.setLetterSpacing(0.04f);
        addView(label);

        list = new LinearLayout(c);
        list.setOrientation(VERTICAL);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(4);
        addView(list, lp);

        String cached = prefs().getString("json", null);
        if (cached != null) { try { show(new JSONArray(cached)); } catch (Exception ignored) { } }
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
                try {
                    final JSONArray a = fetch(token);
                    prefs().edit().putString("json", a.toString()).putLong("at", System.currentTimeMillis()).apply();
                    ui.post(() -> show(a));
                } catch (Exception e) {
                    Log.w(TAG, "fetch failed: " + e);
                }
            }).start();
            ui.postDelayed(this, REFRESH_MS);
        }
    };

    private SharedPreferences prefs() { return getContext().getSharedPreferences("calendar", Context.MODE_PRIVATE); }
    private String token() { return getContext().getSharedPreferences("cameras", Context.MODE_PRIVATE).getString("token", ""); }

    private static String iso(Calendar c) {
        return new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ", Locale.UK).format(c.getTime());
    }

    private static JSONArray fetch(String token) throws Exception {
        if (token == null || token.isEmpty()) { Log.w(TAG, "no token"); return new JSONArray(); }
        Calendar s = Calendar.getInstance();
        s.set(Calendar.HOUR_OF_DAY, 0); s.set(Calendar.MINUTE, 0); s.set(Calendar.SECOND, 0); s.set(Calendar.MILLISECOND, 0);
        Calendar e = (Calendar) s.clone(); e.add(Calendar.DAY_OF_MONTH, DAYS);
        String url = BASE + "/api/calendars/" + ENTITY
                + "?start=" + URLEncoder.encode(iso(s), "UTF-8")
                + "&end=" + URLEncoder.encode(iso(e), "UTF-8");
        HttpURLConnection con = (HttpURLConnection) new URL(url).openConnection();
        con.setConnectTimeout(10000);
        con.setReadTimeout(10000);
        con.setRequestProperty("Authorization", "Bearer " + token);
        con.setRequestProperty("Accept", "application/json");
        int code = con.getResponseCode();
        Log.i(TAG, "calendar HTTP " + code);
        if (code != 200) { con.disconnect(); return new JSONArray(); }
        try (BufferedReader r = new BufferedReader(new InputStreamReader(con.getInputStream(), "UTF-8"))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = r.readLine()) != null) sb.append(line);
            return new JSONArray(sb.toString());
        } finally {
            con.disconnect();
        }
    }

    private void show(JSONArray a) {
        list.removeAllViews();
        List<JSONObject> evs = new ArrayList<>();
        for (int i = 0; i < a.length(); i++) { JSONObject o = a.optJSONObject(i); if (o != null) evs.add(o); }
        Collections.sort(evs, (x, y) -> startKey(x).compareTo(startKey(y)));
        if (evs.isEmpty()) { list.addView(line("", "Nothing in the next 7 days", true)); return; }
        int shown = Math.min(evs.size(), MAX_ROWS);
        for (int i = 0; i < shown; i++) {
            JSONObject o = evs.get(i);
            list.addView(line(dayLabel(o), detail(o), false));
        }
        if (evs.size() > shown) list.addView(line("", "+" + (evs.size() - shown) + " more", true));
    }

    private static String startKey(JSONObject o) {
        JSONObject s = o.optJSONObject("start");
        if (s == null) return "";
        return s.optString("dateTime", s.optString("date", ""));
    }

    private static String datePart(JSONObject o) {
        JSONObject s = o.optJSONObject("start");
        if (s == null) return "";
        String dt = s.optString("dateTime", "");
        if (dt.length() >= 10) return dt.substring(0, 10);
        return s.optString("date", "");
    }

    private static String dayLabel(JSONObject o) {
        String d = datePart(o);
        if (d.length() < 10) return "";
        String today = new SimpleDateFormat("yyyy-MM-dd", Locale.UK).format(new Date());
        if (d.compareTo(today) <= 0) return "Today";
        try {
            Date dt = new SimpleDateFormat("yyyy-MM-dd", Locale.UK).parse(d);
            return new SimpleDateFormat("EEE", Locale.UK).format(dt);   // Mon, Tue...
        } catch (Exception e) {
            return "";
        }
    }

    /** Time + title for a timed event, or just the title for an all-day one. */
    private static String detail(JSONObject o) {
        JSONObject s = o.optJSONObject("start");
        String summary = o.optString("summary", "(busy)");
        String dt = s == null ? "" : s.optString("dateTime", "");
        if (dt.length() >= 16) return dt.substring(11, 16) + "  " + summary;   // HH:mm
        return summary;
    }

    private LinearLayout line(String day, String title, boolean dim) {
        LinearLayout r = new LinearLayout(getContext());
        r.setOrientation(HORIZONTAL);
        r.setPadding(0, dp(1), 0, dp(1));
        TextView t = text(13, 0xDDEBEBF5, "sans-serif-medium");
        t.setText(day);
        r.addView(t, new LayoutParams(dp(50), LayoutParams.WRAP_CONTENT));
        TextView n = text(13, dim ? 0x99FFFFFF : Color.WHITE, dim ? "sans-serif" : "sans-serif-medium");
        n.setText(title);
        n.setSingleLine(true);
        n.setEllipsize(TextUtils.TruncateAt.END);
        r.addView(n, new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1));
        return r;
    }

    private int dp(float v) { return Tiles.dp(getContext(), v); }

    private TextView text(float sp, int colour, String family) {
        TextView t = new TextView(getContext());
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(colour);
        t.setTypeface(Typeface.create(family, Typeface.NORMAL));
        t.setIncludeFontPadding(false);
        t.setShadowLayer(dp(3), 0, dp(1), 0x66000000);
        return t;
    }
}
