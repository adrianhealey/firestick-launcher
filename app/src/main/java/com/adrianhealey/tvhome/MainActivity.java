package com.adrianhealey.tvhome;

import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.Uri;
import android.Manifest;
import android.content.ContentUris;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import android.graphics.PorterDuff;
import android.widget.Toast;
import android.content.IntentFilter;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
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

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
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
    private View scrim;
    private static final int REQ_BG = 7001;
    private static final int REQ_PERM = 7002;
    private FrameLayout preview;
    private TextView title, hint;
    private LinearLayout row;
    private HorizontalScrollView rowScroll;
    private TextView emptyRow;
    private WeatherPanel weather;
    private CalendarPanel calendar;
    private TextView vpnLabel;
    private FrameLayout vpnBtn;
    private int vpnColor = 0xFFFF3B30;
    private StepsBadge steps;   // red until the VPN is confirmed up

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
        if (steps != null) steps.refreshIfStale();
        updateVpnStatus();
        ui.removeCallbacks(vpnPoll);
        ui.post(vpnPoll);
    }

    @Override
    protected void onPause() {
        super.onPause();
        ui.removeCallbacks(vpnPoll);
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
        importDropZone();
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Theme.GTA ? 0xFF160A33 : Color.BLACK);
        root.setClipChildren(false);

        backdrop = new ImageView(this);
        backdrop.setScaleType(ImageView.ScaleType.CENTER_CROP);
        backdropNow = baseBackdrop();
        backdrop.setImageDrawable(backdropNow);
        root.addView(backdrop, match());

        scrim = new View(this);
        applyScrim();
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
        steps = new StepsBadge(this);
        top.addView(steps, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(52)));
        top.addView(new View(this), new LinearLayout.LayoutParams(dp(16), 1));
        vpnBtn = new FrameLayout(this);
        vpnBtn.setFocusable(true);
        vpnBtn.setClickable(true);
        vpnBtn.setContentDescription("VPN");
        final GradientDrawable vpnBg = new GradientDrawable();
        vpnBg.setShape(GradientDrawable.RECTANGLE);
        vpnBg.setCornerRadius(dp(26));
        vpnBg.setColor(0x40FFFFFF);
        if (Theme.GTA) { vpnBg.setColor(Theme.PILL_IDLE); vpnBg.setStroke(dp(1), Theme.PILL_STROKE); }
        vpnBtn.setBackground(vpnBg);
        vpnBtn.setElevation(dp(2));
        vpnBtn.setPadding(dp(16), 0, dp(16), 0);
        vpnLabel = new TextView(this);
        vpnLabel.setText("VPN OFF");
        vpnLabel.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        vpnLabel.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        vpnLabel.setTextColor(vpnColor);
        vpnBtn.addView(vpnLabel, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER));
        vpnBtn.setOnFocusChangeListener((v, has) -> {
            vpnBg.setColor(has ? (Theme.GTA ? Theme.PILL_FOCUS : Color.WHITE)
                               : (Theme.GTA ? Theme.PILL_IDLE : 0x40FFFFFF));
            vpnLabel.setTextColor(has ? 0xFF1C1C1E : vpnColor);
            v.animate().scaleX(has ? 1.15f : 1f).scaleY(has ? 1.15f : 1f).setDuration(120).start();
        });
        vpnBtn.setOnClickListener(v -> {
            Intent vi = getPackageManager().getLaunchIntentForPackage("com.ixolit.ipvanish");
            if (vi != null) { vi.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK); startActivity(vi); }
            else Toast.makeText(this, "IPVanish is not installed", Toast.LENGTH_SHORT).show();
        });
        top.addView(vpnBtn, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(52)));
        top.addView(new View(this), new LinearLayout.LayoutParams(dp(16), 1));
        if (Theme.GTA) {
            top.addView(Tiles.roundButton(this, R.drawable.ic_wallpaper, "Background",
                    v -> backgroundMenu()), new LinearLayout.LayoutParams(dp(52), dp(52)));
            top.addView(new View(this), new LinearLayout.LayoutParams(dp(16), 1));
        }
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

        // shelf: weather on the left, the wide 7-day family calendar on the right
        LinearLayout shelf = new LinearLayout(this);
        shelf.setOrientation(LinearLayout.HORIZONTAL);
        shelf.setGravity(Gravity.CENTER_VERTICAL);
        shelf.setClipChildren(false);
        weather = new WeatherPanel(this);
        shelf.addView(weather, new LinearLayout.LayoutParams(dp(330), ViewGroup.LayoutParams.WRAP_CONTENT));
        // flexible gap between the two boxes
        View gap = new View(this);
        shelf.addView(gap, new LinearLayout.LayoutParams(0, 1, 1f));
        calendar = new CalendarPanel(this);
        LinearLayout.LayoutParams calLp = new LinearLayout.LayoutParams(dp(430), dp(210));
        shelf.addView(calendar, calLp);
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
    // ---- VPN status: green when a VPN (IPVanish) is up, red when not ----
    private final Runnable vpnPoll = new Runnable() {
        @Override public void run() {
            updateVpnStatus();
            ui.postDelayed(this, 5000);
        }
    };

    private boolean vpnConnected() {
        ConnectivityManager cm = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
        if (cm == null) return false;
        try {
            for (Network n : cm.getAllNetworks()) {
                NetworkCapabilities nc = cm.getNetworkCapabilities(n);
                if (nc != null && nc.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) return true;
            }
        } catch (Exception ignored) { }
        return false;
    }

    private void updateVpnStatus() {
        boolean on = vpnConnected();
        vpnColor = on ? 0xFF34C759 : 0xFFFF3B30;
        if (vpnLabel != null) {
            vpnLabel.setText(on ? "VPN ON" : "VPN OFF");
            if (vpnBtn != null && !vpnBtn.isFocused()) vpnLabel.setTextColor(vpnColor);
        }
    }

    private void showShelf(AppRepo.App a) {
        if (Theme.GTA || hasCustomBg()) return;   // keep the fixed sunset / custom backdrop
        if (a == null) {
            setBackdrop(new ColorDrawable(0xFF101014));
            return;
        }

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

    // -------------------------------------------------- custom background (Shield)

    private File customBg() { return new File(getFilesDir(), "custom_bg.jpg"); }
    private boolean hasCustomBg() { return customBg().exists(); }

    /** The backdrop to show when no app is focused: the user's own image if set, else the theme default. */
    private Drawable baseBackdrop() {
        if (hasCustomBg()) {
            Bitmap bm = BitmapFactory.decodeFile(customBg().getPath());
            if (bm != null) return new BitmapDrawable(getResources(), bm);
        }
        return Theme.GTA ? Theme.sunset() : new ColorDrawable(0xFF101014);
    }

    /** A neutral readability scrim over a custom photo, or the themed scrim otherwise. */
    private void applyScrim() {
        if (scrim == null) return;
        if (hasCustomBg()) {
            scrim.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                    new int[]{0x99000000, 0x22000000, 0xB3000000}));
        } else {
            scrim.setBackground(Theme.GTA ? Theme.scrim()
                    : new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                    new int[]{0x8C000000, 0x66000000, 0xE6000000}));
        }
    }

    /** Clicking the Background button: Android TV's file picker is unreliable, so the primary
     *  option loads the newest image in the gallery (download one, then pick it here). */
    private void backgroundMenu() {
        java.util.ArrayList<String> items = new java.util.ArrayList<>();
        items.add("Use my latest downloaded image");
        items.add("Browse for a file…");
        if (hasCustomBg()) items.add("Remove background");
        final String[] arr = items.toArray(new String[0]);
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("Background")
                .setItems(arr, (d, which) -> {
                    String choice = arr[which];
                    if (choice.startsWith("Use my latest")) {
                        loadLatestImage();
                    } else if (choice.startsWith("Browse")) {
                        pickBackground();
                    } else {
                        customBg().delete();
                        applyScrim();
                        setBackdrop(baseBackdrop());
                        Toast.makeText(this, "Background removed", Toast.LENGTH_SHORT).show();
                    }
                })
                .show();
    }

    /** Load the newest image the user has saved. Scans the filesystem directly because Android TV's
     *  media index frequently misses the Downloader app's subfolder; falls back to MediaStore. */
    private void loadLatestImage() {
        String perm = Build.VERSION.SDK_INT >= 33
                ? Manifest.permission.READ_MEDIA_IMAGES : Manifest.permission.READ_EXTERNAL_STORAGE;
        if (checkSelfPermission(perm) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{perm}, REQ_PERM);
            return;
        }
        File f = newestImageFile();
        boolean ok = f != null && saveBackgroundFile(f);
        if (!ok) {                       // fall back to the gallery index
            Uri newest = newestImage();
            ok = newest != null && saveBackground(newest);
        }
        if (ok) {
            applyScrim();
            setBackdrop(baseBackdrop());
            Toast.makeText(this, "Background updated", Toast.LENGTH_SHORT).show();
        } else {
            Toast.makeText(this, "No image found — download one to your Downloads folder first", Toast.LENGTH_LONG).show();
        }
    }

    /** Newest image file across Download (incl. subfolders), Pictures and DCIM. */
    private File newestImageFile() {
        File ext = Environment.getExternalStorageDirectory();
        File[] roots = {new File(ext, "Download"), new File(ext, "Pictures"), new File(ext, "DCIM")};
        File best = null;
        for (File r : roots) best = newestUnder(r, best, 0);
        return best;
    }

    private File newestUnder(File dir, File best, int depth) {
        if (dir == null || !dir.isDirectory() || depth > 3) return best;
        File[] kids = dir.listFiles();
        if (kids == null) return best;
        for (File f : kids) {
            if (f.isDirectory()) { best = newestUnder(f, best, depth + 1); continue; }
            String n = f.getName().toLowerCase();
            if (n.endsWith(".jpg") || n.endsWith(".jpeg") || n.endsWith(".png") || n.endsWith(".webp")) {
                if (best == null || f.lastModified() > best.lastModified()) best = f;
            }
        }
        return best;
    }

    /** Decode a file (down-sampled to ~1080p) and save it as the custom background. */
    private boolean saveBackgroundFile(File src) {
        try {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(src.getPath(), bounds);
            int sample = 1;
            while (bounds.outWidth > 0 && bounds.outWidth / (sample * 2) >= 1920) sample *= 2;
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inSampleSize = sample;
            Bitmap bm = BitmapFactory.decodeFile(src.getPath(), opts);
            if (bm == null) return false;
            try (FileOutputStream fos = new FileOutputStream(customBg())) {
                bm.compress(Bitmap.CompressFormat.JPEG, 90, fos);
            }
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private Uri newestImage() {
        Uri coll = MediaStore.Images.Media.EXTERNAL_CONTENT_URI;
        String[] proj = {MediaStore.Images.Media._ID};
        try (Cursor c = getContentResolver().query(coll, proj, null, null,
                MediaStore.Images.Media.DATE_ADDED + " DESC")) {
            if (c != null && c.moveToFirst()) {
                long id = c.getLong(c.getColumnIndexOrThrow(MediaStore.Images.Media._ID));
                return ContentUris.withAppendedId(coll, id);
            }
        } catch (Exception e) {
            // fall through
        }
        return null;
    }

    @Override
    public void onRequestPermissionsResult(int req, String[] perms, int[] res) {
        super.onRequestPermissionsResult(req, perms, res);
        if (req == REQ_PERM) {
            if (res.length > 0 && res[0] == PackageManager.PERMISSION_GRANTED) loadLatestImage();
            else Toast.makeText(this, "Permission needed to read your images", Toast.LENGTH_SHORT).show();
        }
    }

    /** ADB drop-zone: an image left at <externalFilesDir>/background.(jpg|png|webp) is imported on launch. */
    private void importDropZone() {
        File dir = getExternalFilesDir(null);
        if (dir == null) return;
        for (String name : new String[]{"background.jpg", "background.jpeg", "background.png", "background.webp"}) {
            File f = new File(dir, name);
            if (f.exists() && f.length() > 0) {
                try {
                    Bitmap bm = BitmapFactory.decodeFile(f.getPath());
                    if (bm != null) {
                        try (FileOutputStream fos = new FileOutputStream(customBg())) {
                            bm.compress(Bitmap.CompressFormat.JPEG, 90, fos);
                        }
                    }
                } catch (Exception e) {
                    // ignore
                }
                f.delete();
                break;
            }
        }
    }

    private void pickBackground() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("image/*");
        try { startActivityForResult(i, REQ_BG); }
        catch (Exception e) { Toast.makeText(this, "No image picker available", Toast.LENGTH_SHORT).show(); }
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req == REQ_BG && res == RESULT_OK && data != null && data.getData() != null) {
            if (saveBackground(data.getData())) {
                applyScrim();
                setBackdrop(baseBackdrop());
                Toast.makeText(this, "Background updated", Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(this, "Couldn't use that image", Toast.LENGTH_SHORT).show();
            }
        }
    }

    /** Decode the chosen image (down-sampled to ~1080p) and save it as the custom background. */
    private boolean saveBackground(Uri uri) {
        try {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            try (InputStream s = getContentResolver().openInputStream(uri)) {
                BitmapFactory.decodeStream(s, null, bounds);
            }
            int sample = 1;
            while (bounds.outWidth > 0 && bounds.outWidth / (sample * 2) >= 1920) sample *= 2;
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inSampleSize = sample;
            Bitmap bm;
            try (InputStream s = getContentResolver().openInputStream(uri)) {
                bm = BitmapFactory.decodeStream(s, null, opts);
            }
            if (bm == null) return false;
            try (FileOutputStream fos = new FileOutputStream(customBg())) {
                bm.compress(Bitmap.CompressFormat.JPEG, 90, fos);
            }
            return true;
        } catch (Exception e) {
            return false;
        }
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
