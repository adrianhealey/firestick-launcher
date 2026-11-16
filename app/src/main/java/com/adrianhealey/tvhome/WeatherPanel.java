package com.adrianhealey.tvhome;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Apple Weather-style panel: current temperature and conditions, then a 7-day
 * forecast with coloured temperature-range bars. Data from Open-Meteo (no key needed).
 */
final class WeatherPanel extends LinearLayout {

    // St Helens, Merseyside
    static final String PLACE = "Shays Farm";
    static final double LAT = 53.20127, LON = -2.60761;
    private static final long REFRESH_MS = 30 * 60 * 1000L;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final TextView temp, place, cond, hilo;
    private final LinearLayout days;

    WeatherPanel(Context c) {
        super(c);
        setOrientation(VERTICAL);
        setPadding(dp(18), dp(12), dp(18), dp(8));
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(18));
        bg.setColor(0x59101826);
        bg.setStroke(dp(1), 0x26FFFFFF);
        setBackground(bg);

        // header: big temperature on the left, place / conditions on the right
        LinearLayout head = new LinearLayout(c);
        head.setOrientation(HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        temp = text(40, Color.WHITE, "sans-serif-light");
        temp.setText("--°");
        head.addView(temp);
        LinearLayout words = new LinearLayout(c);
        words.setOrientation(VERTICAL);
        words.setPadding(dp(14), 0, 0, 0);
        place = text(15, Color.WHITE, "sans-serif-medium");
        place.setText(PLACE);
        cond = text(13, 0xE6FFFFFF, "sans-serif");
        hilo = text(13, 0xE6FFFFFF, "sans-serif");
        words.addView(place);
        words.addView(cond);
        words.addView(hilo);
        head.addView(words);
        addView(head);

        View line = new View(c);
        line.setBackgroundColor(0x33FFFFFF);
        LayoutParams lp = new LayoutParams(LayoutParams.MATCH_PARENT, Math.max(1, dp(0.7f)));
        lp.topMargin = dp(8);
        lp.bottomMargin = dp(4);
        addView(line, lp);

        TextView label = text(11, 0x99FFFFFF, "sans-serif-medium");
        label.setText("📅  7-DAY FORECAST");
        label.setLetterSpacing(0.04f);
        addView(label);

        days = new LinearLayout(c);
        days.setOrientation(VERTICAL);
        addView(days, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));

        String cached = prefs().getString("json", null);
        if (cached != null) {
            try { show(new JSONObject(cached)); } catch (Exception ignored) { }
        }
    }

    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        ui.post(refresh);
    }

    @Override protected void onDetachedFromWindow() {
        ui.removeCallbacks(refresh);
        super.onDetachedFromWindow();
    }

    /** Fetch again soon if the last update is old (called when the home screen reappears). */
    void refreshIfStale() {
        if (System.currentTimeMillis() - prefs().getLong("at", 0) > REFRESH_MS) {
            ui.removeCallbacks(refresh);
            ui.post(refresh);
        }
    }

    private final Runnable refresh = new Runnable() {
        @Override public void run() {
            new Thread(() -> {
                try {
                    final JSONObject j = fetch();
                    prefs().edit().putString("json", j.toString())
                            .putLong("at", System.currentTimeMillis()).apply();
                    ui.post(() -> show(j));
                } catch (Exception ignored) {
                    // keep showing the last good forecast
                }
            }).start();
            ui.postDelayed(this, REFRESH_MS);
        }
    };

    private SharedPreferences prefs() {
        return getContext().getSharedPreferences("weather", Context.MODE_PRIVATE);
    }

    private static JSONObject fetch() throws Exception {
        URL url = new URL("https://api.open-meteo.com/v1/forecast?latitude=" + LAT + "&longitude=" + LON
                + "&current=temperature_2m,weather_code,is_day"
                + "&daily=weather_code,temperature_2m_max,temperature_2m_min,precipitation_probability_max"
                + "&timezone=Europe%2FLondon&forecast_days=7");
        HttpURLConnection con = (HttpURLConnection) url.openConnection();
        con.setConnectTimeout(10000);
        con.setReadTimeout(10000);
        try (BufferedReader r = new BufferedReader(new InputStreamReader(con.getInputStream(), "UTF-8"))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = r.readLine()) != null) sb.append(line);
            return new JSONObject(sb.toString());
        } finally {
            con.disconnect();
        }
    }

    // ------------------------------------------------------------------ show

    private void show(JSONObject j) {
        try {
            JSONObject cur = j.getJSONObject("current");
            JSONObject d = j.getJSONObject("daily");
            JSONArray time = d.getJSONArray("time");
            JSONArray code = d.getJSONArray("weather_code");
            JSONArray hi = d.getJSONArray("temperature_2m_max");
            JSONArray lo = d.getJSONArray("temperature_2m_min");
            JSONArray rain = d.optJSONArray("precipitation_probability_max");

            double now = cur.getDouble("temperature_2m");
            boolean day = cur.optInt("is_day", 1) == 1;
            temp.setText(Math.round(now) + "°");
            cond.setText(describe(cur.getInt("weather_code")));
            hilo.setText("H:" + Math.round(hi.getDouble(0)) + "°  L:" + Math.round(lo.getDouble(0)) + "°");

            double weekLo = Double.MAX_VALUE, weekHi = -Double.MAX_VALUE;
            for (int i = 0; i < time.length(); i++) {
                weekLo = Math.min(weekLo, lo.getDouble(i));
                weekHi = Math.max(weekHi, hi.getDouble(i));
            }

            SimpleDateFormat in = new SimpleDateFormat("yyyy-MM-dd", Locale.UK);
            SimpleDateFormat out = new SimpleDateFormat("EEE", Locale.UK);
            days.removeAllViews();
            for (int i = 0; i < time.length(); i++) {
                String name;
                if (i == 0) name = "Today";
                else {
                    Date dt = in.parse(time.getString(i));
                    name = dt == null ? "" : out.format(dt);
                }
                int c = code.getInt(i);
                int pop = rain == null ? 0 : rain.optInt(i, 0);
                days.addView(dayRow(name, icon(c, i == 0 ? day : true), pop, lo.getDouble(i), hi.getDouble(i),
                        weekLo, weekHi, i == 0 ? now : Double.NaN));
            }
        } catch (Exception ignored) { }
    }

    private View dayRow(String name, String icon, int pop, double lo, double hi,
                        double weekLo, double weekHi, double now) {
        LinearLayout r = new LinearLayout(getContext());
        r.setOrientation(HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setLayoutParams(new LayoutParams(LayoutParams.MATCH_PARENT, dp(23)));

        TextView n = text(14, Color.WHITE, "sans-serif-medium");
        n.setText(name);
        r.addView(n, new LayoutParams(dp(52), LayoutParams.WRAP_CONTENT));

        // icon, with chance of rain underneath in blue (like Apple Weather)
        LinearLayout ic = new LinearLayout(getContext());
        ic.setOrientation(HORIZONTAL);
        ic.setGravity(Gravity.CENTER_VERTICAL);
        TextView i = text(15, Color.WHITE, "sans-serif");
        i.setText(icon);
        i.setGravity(Gravity.CENTER);
        ic.addView(i);
        if (pop >= 30) {
            TextView p = text(10, 0xFF81D4FA, "sans-serif-medium");
            p.setText(" " + pop + "%");
            ic.addView(p);
        }
        r.addView(ic, new LayoutParams(dp(58), LayoutParams.WRAP_CONTENT));

        TextView l = text(14, 0x99FFFFFF, "sans-serif-medium");
        l.setText(Math.round(lo) + "°");
        l.setGravity(Gravity.END);
        r.addView(l, new LayoutParams(dp(34), LayoutParams.WRAP_CONTENT));

        RangeBar bar = new RangeBar(getContext(), lo, hi, weekLo, weekHi, now);
        LayoutParams bp = new LayoutParams(0, dp(6), 1);
        bp.leftMargin = dp(10);
        bp.rightMargin = dp(10);
        r.addView(bar, bp);

        TextView h = text(14, Color.WHITE, "sans-serif-medium");
        h.setText(Math.round(hi) + "°");
        r.addView(h, new LayoutParams(dp(30), LayoutParams.WRAP_CONTENT));
        return r;
    }

    // ------------------------------------------------------------ weather codes

    static String describe(int c) {
        if (c == 0) return "Clear";
        if (c == 1) return "Mostly Clear";
        if (c == 2) return "Partly Cloudy";
        if (c == 3) return "Cloudy";
        if (c == 45 || c == 48) return "Foggy";
        if (c >= 51 && c <= 57) return "Drizzle";
        if (c >= 61 && c <= 67) return c >= 65 ? "Heavy Rain" : "Rain";
        if (c >= 71 && c <= 77) return "Snow";
        if (c >= 80 && c <= 82) return "Showers";
        if (c == 85 || c == 86) return "Snow Showers";
        if (c >= 95) return "Thunderstorms";
        return "";
    }

    static String icon(int c, boolean day) {
        if (c == 0) return day ? "☀️" : "🌙";
        if (c == 1) return day ? "🌤️" : "🌙";
        if (c == 2) return day ? "⛅" : "☁️";
        if (c == 3) return "☁️";
        if (c == 45 || c == 48) return "🌫️";
        if (c >= 51 && c <= 57) return "🌦️";
        if (c >= 61 && c <= 67) return "🌧️";
        if (c >= 71 && c <= 77) return "❄️";
        if (c >= 80 && c <= 82) return "🌦️";
        if (c == 85 || c == 86) return "🌨️";
        if (c >= 95) return "⛈️";
        return "☁️";
    }

    /** Apple's temperature colours: blue when cold through green and yellow to orange. */
    static int tempColour(double t) {
        double[] at = {-5, 5, 12, 18, 25, 32};
        int[] col = {0xFF5AC8FA, 0xFF64D2FF, 0xFF7ED957, 0xFFFFD60A, 0xFFFF9F0A, 0xFFFF453A};
        if (t <= at[0]) return col[0];
        for (int i = 1; i < at.length; i++) {
            if (t <= at[i]) {
                float f = (float) ((t - at[i - 1]) / (at[i] - at[i - 1]));
                return blend(col[i - 1], col[i], f);
            }
        }
        return col[col.length - 1];
    }

    private static int blend(int a, int b, float f) {
        return Color.rgb(
                Math.round(Color.red(a) + (Color.red(b) - Color.red(a)) * f),
                Math.round(Color.green(a) + (Color.green(b) - Color.green(a)) * f),
                Math.round(Color.blue(a) + (Color.blue(b) - Color.blue(a)) * f));
    }

    /** Rounded track with the day's range highlighted, and a dot for "now" on today. */
    static final class RangeBar extends View {
        private final double lo, hi, wLo, wHi, now;
        private final Paint track = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint dot = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF r = new RectF();

        RangeBar(Context c, double lo, double hi, double wLo, double wHi, double now) {
            super(c);
            this.lo = lo; this.hi = hi; this.wLo = wLo; this.wHi = wHi; this.now = now;
            track.setColor(0x33000000);
            dot.setColor(Color.WHITE);
        }

        @Override protected void onDraw(Canvas cv) {
            float w = getWidth(), h = getHeight(), rad = h / 2f;
            r.set(0, 0, w, h);
            cv.drawRoundRect(r, rad, rad, track);
            double span = Math.max(1, wHi - wLo);
            float x0 = (float) ((lo - wLo) / span * w);
            float x1 = (float) ((hi - wLo) / span * w);
            if (x1 - x0 < h) x1 = Math.min(w, x0 + h);
            fill.setShader(new LinearGradient(x0, 0, x1, 0, tempColour(lo), tempColour(hi), Shader.TileMode.CLAMP));
            r.set(x0, 0, x1, h);
            cv.drawRoundRect(r, rad, rad, fill);
            if (!Double.isNaN(now)) {
                float x = (float) ((now - wLo) / span * w);
                x = Math.max(rad, Math.min(w - rad, x));
                dot.setColor(0xFF000000);
                cv.drawCircle(x, h / 2f, rad + 1.5f, dot);
                dot.setColor(Color.WHITE);
                cv.drawCircle(x, h / 2f, rad, dot);
            }
        }
    }

    // ------------------------------------------------------------------ util

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
