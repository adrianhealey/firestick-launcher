package com.adrianhealey.tvhome;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.PorterDuff;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Apple TV-style app icons, buttons and image helpers. */
final class Tiles {
    private Tiles() { }

    interface Listener {
        void onOpen(AppRepo.App a, View tile);
        void onHold(AppRepo.App a, View tile);
        void onMenu(AppRepo.App a, View tile);
        void onFocus(AppRepo.App a, View tile);
    }

    static final float FOCUS_SCALE = 1.14f;

    static int dp(Context c, float v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                c.getResources().getDisplayMetrics()));
    }

    // ------------------------------------------------------------- bitmaps

    static Bitmap toBitmap(Drawable d, int w, int h) {
        Bitmap b = Bitmap.createBitmap(Math.max(1, w), Math.max(1, h), Bitmap.Config.ARGB_8888);
        if (d == null) return b;
        Drawable copy = d.getConstantState() != null ? d.getConstantState().newDrawable().mutate() : d;
        copy.setBounds(0, 0, w, h);
        copy.draw(new Canvas(b));
        return b;
    }

    /** Cheap, soft blur: shrink right down, then scale back up with filtering. */
    static Bitmap blur(Drawable d) {
        Bitmap tiny = toBitmap(d, 16, 9);
        Bitmap mid = Bitmap.createScaledBitmap(tiny, 64, 36, true);
        return Bitmap.createScaledBitmap(mid, 320, 180, true);
    }

    /** Average colour of the visible pixels, darkened a touch to sit behind a white icon. */
    static int averageColour(Drawable d) {
        if (d == null) return 0xFF2C2C2E;
        Bitmap b = toBitmap(d, 24, 24);
        long r = 0, g = 0, bl = 0, n = 0;
        for (int y = 0; y < 24; y++) {
            for (int x = 0; x < 24; x++) {
                int p = b.getPixel(x, y);
                if (Color.alpha(p) < 128) continue;
                r += Color.red(p); g += Color.green(p); bl += Color.blue(p); n++;
            }
        }
        if (n == 0) return 0xFF2C2C2E;
        float[] hsv = new float[3];
        Color.RGBToHSV((int) (r / n), (int) (g / n), (int) (bl / n), hsv);
        hsv[2] = Math.min(hsv[2], 0.55f);           // keep it dark enough for contrast
        hsv[1] = Math.min(hsv[1] * 1.1f, 1f);
        return Color.HSVToColor(hsv);
    }

    // ---------------------------------------------------------------- cards

    /** The picture part of an icon: banner fills it, or icon centred on its own colour. */
    static FrameLayout card(Context c, AppRepo.App a, int w, int h, int radius) {
        FrameLayout card = new FrameLayout(c);
        GradientDrawable bg = Theme.GTA
                ? new GradientDrawable(GradientDrawable.Orientation.TL_BR, Theme.tileGradient(a.pkg))
                : new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{lighten(a.tint), a.tint});
        bg.setCornerRadius(radius);
        if (Theme.GTA) bg.setStroke(dp(c, 1), Theme.TILE_EDGE);
        card.setBackground(bg);
        card.setOutlineProvider(new ViewOutlineProvider() {
            @Override public void getOutline(View v, Outline o) {
                o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), radius);
            }
        });
        card.setClipToOutline(true);

        ImageView img = new ImageView(c);
        img.setImageDrawable(a.art);
        if (a.isBanner) {
            img.setScaleType(ImageView.ScaleType.CENTER_CROP);
            card.addView(img, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT));
        } else {
            img.setScaleType(ImageView.ScaleType.FIT_CENTER);
            int s = Math.round(h * 0.56f);
            card.addView(img, new FrameLayout.LayoutParams(s, s, Gravity.CENTER));
        }
        return card;
    }

    private static int lighten(int c) {
        float[] hsv = new float[3];
        Color.colorToHSV(c, hsv);
        hsv[2] = Math.min(1f, hsv[2] + 0.12f);
        return Color.HSVToColor(hsv);
    }

    /**
     * A focusable app icon: rounded card that lifts, grows and catches the light when
     * selected, with the app name fading in underneath (like Apple TV).
     */
    static View appTile(final Context c, final AppRepo.App a, int w, int h, final Listener l) {
        final LinearLayout box = new LinearLayout(c);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER_HORIZONTAL);
        box.setClipChildren(false);
        box.setClipToPadding(false);
        box.setFocusable(true);
        box.setClickable(true);
        box.setTag(a.pkg);

        final FrameLayout card = card(c, a, w, h, dp(c, 12));
        card.setElevation(dp(c, 3));

        // light sheen across the top of the icon when selected
        final View sheen = new View(c);
        GradientDrawable sh = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                Theme.GTA ? new int[]{0x66FF2E9A, 0x1117D9D9, 0x00000000}
                          : new int[]{0x55FFFFFF, 0x10FFFFFF, 0x00FFFFFF});
        sheen.setBackground(sh);
        sheen.setAlpha(0f);
        card.addView(sheen, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        box.addView(card, new LinearLayout.LayoutParams(w, h));

        final TextView label = new TextView(c);
        label.setText(a.label);
        label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        label.setTextColor(Color.WHITE);
        label.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        label.setSingleLine(true);
        label.setEllipsize(TextUtils.TruncateAt.END);
        label.setGravity(Gravity.CENTER);
        label.setShadowLayer(dp(c, 4), 0, dp(c, 1), 0x99000000);
        label.setAlpha(0f);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(w,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(c, 12);
        box.addView(label, lp);

        final float lift = h * (FOCUS_SCALE - 1f) / 2f;
        box.setOnFocusChangeListener((v, has) -> {
            DecelerateInterpolator ease = new DecelerateInterpolator(2f);
            card.animate().scaleX(has ? FOCUS_SCALE : 1f).scaleY(has ? FOCUS_SCALE : 1f)
                    .translationZ(has ? dp(c, 22) : 0).setInterpolator(ease).setDuration(180).start();
            sheen.animate().alpha(has ? 1f : 0f).setDuration(180).start();
            label.animate().alpha(has ? 1f : 0f).translationY(has ? lift : 0f)
                    .setDuration(180).start();
            if (has && l != null) l.onFocus(a, box);
        });
        box.setOnClickListener(v -> { if (l != null) l.onOpen(a, box); });
        box.setOnLongClickListener(v -> { if (l != null) l.onHold(a, box); return true; });
        box.setOnKeyListener((v, code, e) -> {
            if (code != KeyEvent.KEYCODE_MENU) return false;
            if (e.getAction() == KeyEvent.ACTION_UP && l != null) l.onMenu(a, box);
            return true;
        });
        return box;
    }

    /** Small round frosted button with an icon (Apps, Settings...). */
    static View roundButton(final Context c, int iconRes, String description, View.OnClickListener click) {
        final FrameLayout f = new FrameLayout(c);
        f.setFocusable(true);
        f.setClickable(true);
        f.setContentDescription(description);
        final GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.OVAL);
        bg.setColor(0x40FFFFFF);
        if (Theme.GTA) { bg.setColor(Theme.PILL_IDLE); bg.setStroke(dp(c, 1), Theme.PILL_STROKE); }
        f.setBackground(bg);
        f.setElevation(dp(c, 2));

        final ImageView icon = new ImageView(c);
        icon.setImageResource(iconRes);
        icon.setColorFilter(Color.WHITE, PorterDuff.Mode.SRC_IN);
        int s = dp(c, 24);
        f.addView(icon, new FrameLayout.LayoutParams(s, s, Gravity.CENTER));

        f.setOnFocusChangeListener((v, has) -> {
            bg.setColor(has ? (Theme.GTA ? Theme.PILL_FOCUS : Color.WHITE)
                            : (Theme.GTA ? Theme.PILL_IDLE : 0x40FFFFFF));
            icon.setColorFilter(has ? 0xFF1C1C1E : Color.WHITE, PorterDuff.Mode.SRC_IN);
            v.animate().scaleX(has ? 1.15f : 1f).scaleY(has ? 1.15f : 1f)
                    .translationZ(has ? dp(c, 10) : 0).setDuration(150).start();
        });
        f.setOnClickListener(click);
        return f;
    }

    /** Pill-shaped text button in the same style. */
    static TextView pill(final Context c, String text, View.OnClickListener click) {
        final TextView t = new TextView(c);
        t.setText(text);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        t.setTextColor(Color.WHITE);
        t.setPadding(dp(c, 22), dp(c, 10), dp(c, 22), dp(c, 10));
        t.setFocusable(true);
        t.setClickable(true);
        final GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(c, 24));
        bg.setColor(0x40FFFFFF);
        if (Theme.GTA) { bg.setColor(Theme.PILL_IDLE); bg.setStroke(dp(c, 1), Theme.PILL_STROKE); }
        t.setBackground(bg);
        t.setOnFocusChangeListener((v, has) -> {
            bg.setColor(has ? (Theme.GTA ? Theme.PILL_FOCUS : Color.WHITE)
                            : (Theme.GTA ? Theme.PILL_IDLE : 0x40FFFFFF));
            t.setTextColor(has ? 0xFF1C1C1E : Color.WHITE);
            v.animate().scaleX(has ? 1.08f : 1f).scaleY(has ? 1.08f : 1f).setDuration(150).start();
        });
        t.setOnClickListener(click);
        return t;
    }
}
