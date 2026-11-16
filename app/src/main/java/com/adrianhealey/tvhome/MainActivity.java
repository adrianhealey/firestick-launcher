package com.adrianhealey.tvhome;

import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.TransitionDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextClock;
import android.widget.TextView;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Apple TV-style home screen: a large "top shelf" preview of the selected app over a
 * blurred backdrop, and one row of apps along the bottom.
 */
public class MainActivity extends Activity implements Tiles.Listener {

    // Sizes in dp. Five icons fit across a 1080p screen.
    static final int SIDE = 48, ICON_W = 156, ICON_H = 94, ICON_GAP = 20;
    static final int PREVIEW_W = 302, PREVIEW_H = 152;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final Map<String, Bitmap> blurCache = new HashMap<>();
    private Map<String, AppRepo.App> apps = new HashMap<>();

    private ImageView backdrop;
    private Drawable backdropNow;
    private FrameLayout preview;
    private TextView title, hint;
    private LinearLayout row;
    private HorizontalScrollView rowScroll;
    private TextView emptyRow;
    private WeatherPanel weather;
    private CalendarPanel calendar;

    private String focusPkg;            // tile to re-focus after a rebuild
    private String movingPkg;           // app being moved, or null
    private ObjectAnimator wiggle;
    private boolean ignoreNextUp;       // the Select release that ended a press-and-hold

    private final BroadcastReceiver packagesChanged = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) { reloadSoon(); }
    };

    private int dp(float v) { return Tiles.dp(this, v); }

    // ---------------------------------------------------------------- setup

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

        apps = AppRepo.load(this);
        renderRow();
    }

    @Override
    protected void onResume() {
        super.onResume();
        // The Apps screen may have changed the home row.
        if (Prefs.consumeChanged(this)) { apps = AppRepo.load(this); renderRow(); }
        if (weather != null) weather.refreshIfStale();
        if (calendar != null) calendar.refreshIfStale();
    }

    @Override
    protected void onDestroy() {
        try { unregisterReceiver(packagesChanged); } catch (Exception ignored) { }
        super.onDestroy();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        stopMoving();
        rowScroll.smoothScrollTo(0, 0);
        if (row.getChildCount() > 0) row.getChildAt(0).requestFocus();
    }

    @Override
    public void onBackPressed() {
        if (movingPkg != null) stopMoving();
        // otherwise nothing: the home screen has nowhere to go back to
    }

    private View buildUi() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        root.setClipChildren(false);

        backdrop = new ImageView(this);
        backdrop.setScaleType(ImageView.ScaleType.CENTER_CROP);
        backdropNow = new ColorDrawable(0xFF101014);
        backdrop.setImageDrawable(backdropNow);
        root.addView(backdrop, match());

        View scrim = new View(this);
        scrim.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0x8C000000, 0x66000000, 0xE6000000}));
        root.addView(scrim, match());

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(SIDE), dp(28), dp(SIDE), dp(26));
        col.setClipChildren(false);
        col.setClipToPadding(false);
        root.addView(col, match());

        // top bar: Apps, Settings, time
        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setClipChildren(false);
        top.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1));
        top.addView(Tiles.roundButton(this, R.drawable.ic_apps, "All apps",
                v -> startActivity(new Intent(this, AppsActivity.class))), new LinearLayout.LayoutParams(dp(52), dp(52)));
        top.addView(new View(this), new LinearLayout.LayoutParams(dp(16), 1));
        top.addView(Tiles.roundButton(this, R.drawable.ic_settings, "Settings",
                v -> AppRepo.openSystemSettings(this)), new LinearLayout.LayoutParams(dp(52), dp(52)));
        top.addView(new View(this), new LinearLayout.LayoutParams(dp(22), 1));
        TextClock clock = new TextClock(this);
        clock.setFormat24Hour("HH:mm");
        clock.setFormat12Hour("HH:mm");
        clock.setTextSize(TypedValue.COMPLEX_UNIT_SP, 24);
        clock.setTextColor(Color.WHITE);
        clock.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        top.addView(clock);
        col.addView(top);

        // top shelf: big preview of the selected app
        LinearLayout shelf = new LinearLayout(this);
        shelf.setOrientation(LinearLayout.HORIZONTAL);
        shelf.setGravity(Gravity.CENTER_VERTICAL);
        shelf.setClipChildren(false);
        preview = new FrameLayout(this);
        preview.setElevation(dp(16));
        LinearLayout leftCol = new LinearLayout(this);
        leftCol.setOrientation(LinearLayout.VERTICAL);
        leftCol.setClipChildren(false);
        leftCol.addView(preview, new LinearLayout.LayoutParams(dp(PREVIEW_W), dp(PREVIEW_H)));
        calendar = new CalendarPanel(this);
        LinearLayout.LayoutParams calLp = new LinearLayout.LayoutParams(dp(PREVIEW_W), ViewGroup.LayoutParams.WRAP_CONTENT);
        calLp.topMargin = dp(12);
        leftCol.addView(calendar, calLp);
        shelf.addView(leftCol, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout words = new LinearLayout(this);
        words.setOrientation(LinearLayout.VERTICAL);
        words.setPadding(dp(28), 0, dp(20), 0);
        title = new TextView(this);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 30);
        title.setMaxLines(2);
        title.setTextColor(Color.WHITE);
        title.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
        title.setShadowLayer(dp(8), 0, dp(2), 0x80000000);
        hint = new TextView(this);
        hint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        hint.setTextColor(0xCCEBEBF5);
        hint.setPadding(0, dp(8), 0, 0);
        words.addView(title);
        words.addView(hint);
        shelf.addView(words, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        weather = new WeatherPanel(this);
        shelf.addView(weather, new LinearLayout.LayoutParams(dp(330), ViewGroup.LayoutParams.WRAP_CONTENT));
        col.addView(shelf, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

        // the home row
        rowScroll = new HorizontalScrollView(this);
        rowScroll.setHorizontalScrollBarEnabled(false);
        rowScroll.setClipChildren(false);
        rowScroll.setClipToPadding(false);
        rowScroll.setPadding(dp(12), dp(16), dp(12), dp(4));
        row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setClipChildren(false);
        row.setClipToPadding(false);
        rowScroll.addView(row);
        col.addView(rowScroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                dp(ICON_H + 70)));

        emptyRow = new TextView(this);
        emptyRow.setText("Open Apps (top right) and press and hold an app to add it here.");
        emptyRow.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        emptyRow.setTextColor(0xCCEBEBF5);
        emptyRow.setVisibility(View.GONE);
        col.addView(emptyRow);

        return root;
    }

    private static FrameLayout.LayoutParams match() {
        return new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
    }

    // --------------------------------------------------------------- render

    private void reloadSoon() {
        ui.removeCallbacks(reload);
        ui.postDelayed(reload, 500);
    }

    private final Runnable reload = () -> { apps = AppRepo.load(this); renderRow(); };

    private void renderRow() {
        View cur = getCurrentFocus();
        if (cur != null && cur.getTag() instanceof String) focusPkg = (String) cur.getTag();

        List<String> home = AppRepo.homeRow(this, apps);
        row.removeAllViews();
        for (String p : home) {
            View t = Tiles.appTile(this, apps.get(p), dp(ICON_W), dp(ICON_H), this);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.rightMargin = dp(ICON_GAP);
            row.addView(t, lp);
        }
        emptyRow.setVisibility(home.isEmpty() ? View.VISIBLE : View.GONE);
        if (home.isEmpty()) showShelf(null);

        ui.post(() -> {
            if (focusPkg != null) {
                for (int i = 0; i < row.getChildCount(); i++) {
                    View v = row.getChildAt(i);
                    if (focusPkg.equals(v.getTag())) {
                        v.requestFocus();
                        if (focusPkg.equals(movingPkg)) startWiggle(v);
                        return;
                    }
                }
            }
            if (row.getChildCount() > 0) row.getChildAt(0).requestFocus();
        });
    }

    /** Update the top shelf and backdrop for the selected app. */
    private void showShelf(AppRepo.App a) {
        preview.removeAllViews();
        if (a == null) {
            title.setText("TV Home");
            hint.setText("");
            setBackdrop(new ColorDrawable(0xFF101014));
            return;
        }
        preview.addView(Tiles.card(this, a, dp(PREVIEW_W), dp(PREVIEW_H), dp(16)), match());
        title.setText(a.label);
        hint.setText(movingPkg != null
                ? "Use left and right to move it, then press Select."
                : "Press and hold to move or remove");

        Bitmap blur = blurCache.get(a.pkg);
        if (blur == null) {
            blur = Tiles.blur(a.isBanner ? a.art : new ColorDrawable(a.tint));
            blurCache.put(a.pkg, blur);
        }
        setBackdrop(new BitmapDrawable(getResources(), blur));
    }

    private void setBackdrop(Drawable next) {
        TransitionDrawable t = new TransitionDrawable(new Drawable[]{backdropNow, next});
        t.setCrossFadeEnabled(true);
        backdrop.setImageDrawable(t);
        t.startTransition(300);
        backdropNow = next;
    }

    // ------------------------------------------------------------ tile events

    @Override public void onFocus(AppRepo.App a, View tile) {
        showShelf(a);
        // keep the selected icon comfortably on screen
        int x = tile.getLeft() - dp(ICON_W + ICON_GAP);
        rowScroll.smoothScrollTo(Math.max(0, x), 0);
    }

    @Override public void onOpen(AppRepo.App a, View tile) {
        if (movingPkg != null) { stopMoving(); return; }
        AppRepo.launch(this, a);
    }

    @Override public void onHold(AppRepo.App a, View tile) {
        startMoving(a, tile);
        ignoreNextUp = true;
    }

    private void startMoving(AppRepo.App a, View tile) {
        if (movingPkg != null) { stopMoving(); return; }
        // Apple TV: hold to make the icon wiggle, then move it
        movingPkg = a.pkg;
        startWiggle(tile);
        showShelf(a);
    }

    @Override public void onMenu(AppRepo.App a, View tile) {
        String[] items = {"Move", "Remove from Home", "App info"};
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle(a.label)
                .setItems(items, (d, which) -> {
                    if (which == 0) startMoving(a, tile);
                    else if (which == 1) {
                        List<String> home = AppRepo.homeRow(this, apps);
                        home.remove(a.pkg);
                        Prefs.setFavs(this, home);
                        focusPkg = null;
                        renderRow();
                    } else AppRepo.appInfo(this, a);
                })
                .show();
    }

    // ---------------------------------------------------------------- moving

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        if (movingPkg == null) return super.dispatchKeyEvent(e);
        int k = e.getKeyCode();
        if (k == KeyEvent.KEYCODE_DPAD_LEFT || k == KeyEvent.KEYCODE_DPAD_RIGHT) {
            if (e.getAction() == KeyEvent.ACTION_DOWN) shift(k == KeyEvent.KEYCODE_DPAD_LEFT ? -1 : 1);
            return true;
        }
        if (k == KeyEvent.KEYCODE_DPAD_UP || k == KeyEvent.KEYCODE_DPAD_DOWN) return true;
        if (k == KeyEvent.KEYCODE_DPAD_CENTER || k == KeyEvent.KEYCODE_ENTER || k == KeyEvent.KEYCODE_BACK) {
            if (e.getAction() == KeyEvent.ACTION_UP) {
                if (ignoreNextUp && k != KeyEvent.KEYCODE_BACK) ignoreNextUp = false;
                else stopMoving();
            }
            return true;
        }
        return super.dispatchKeyEvent(e);
    }

    private void shift(int by) {
        List<String> home = AppRepo.homeRow(this, apps);
        int i = home.indexOf(movingPkg);
        int j = i + by;
        if (i < 0 || j < 0 || j >= home.size()) return;
        Collections.swap(home, i, j);
        Prefs.setFavs(this, home);
        focusPkg = movingPkg;
        stopWiggle();
        renderRow();
    }

    private void startWiggle(View v) {
        stopWiggle();
        wiggle = ObjectAnimator.ofFloat(v, "rotation", -1.6f, 1.6f);
        wiggle.setDuration(130);
        wiggle.setRepeatMode(ValueAnimator.REVERSE);
        wiggle.setRepeatCount(ValueAnimator.INFINITE);
        wiggle.start();
    }

    private void stopWiggle() {
        if (wiggle != null) {
            wiggle.cancel();
            Object t = wiggle.getTarget();
            if (t instanceof View) ((View) t).setRotation(0);
            wiggle = null;
        }
    }

    private void stopMoving() {
        ignoreNextUp = false;
        stopWiggle();
        String was = movingPkg;
        movingPkg = null;
        if (was != null && apps.containsKey(was)) showShelf(apps.get(was));
    }
}
