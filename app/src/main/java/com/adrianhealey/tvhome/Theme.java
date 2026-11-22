package com.adrianhealey.tvhome;

import android.graphics.drawable.GradientDrawable;
import android.os.Build;

/**
 * GTA VI "Vice City" look. Applied ONLY on the NVIDIA Shield; the Amazon Fire Stick
 * keeps its original Apple-TV glass theme. Everything here is gated behind {@link #GTA}.
 */
final class Theme {
    private Theme() { }

    /** True on the NVIDIA Shield only (Fire Stick reports manufacturer "Amazon"). */
    static final boolean GTA = "NVIDIA".equalsIgnoreCase(Build.MANUFACTURER);

    // ---- Vice City neon palette ----
    static final int PINK   = 0xFFFF2E9A;   // hot magenta-pink
    static final int CYAN   = 0xFF1BE7E7;   // electric cyan
    static final int ORANGE = 0xFFFF6A3C;   // sunset coral
    static final int PURPLE = 0xFF7A1FA2;

    // Pill / button fills
    static final int PILL_IDLE   = 0x552A1150;   // translucent purple glass
    static final int PILL_STROKE = 0xAAFF2E9A;   // neon pink edge
    static final int PILL_FOCUS  = PINK;         // solid hot pink when selected

    // Translucent card (weather / calendar)
    static final int CARD_FILL   = 0x4D2A1150;   // purple glass
    static final int CARD_STROKE = 0x66FF2E9A;   // soft pink edge

    /** Full-screen sunset: deep indigo at the top down to a hot coral horizon. */
    static GradientDrawable sunset() {
        GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0xFF241046, 0xFF4A1A7A, 0xFF9B2070, 0xFFE24A3A});
        g.setGradientType(GradientDrawable.LINEAR_GRADIENT);
        return g;
    }

    /** Neon scrim over the backdrop so content stays readable, letting the sunset glow through. */
    static GradientDrawable scrim() {
        return new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0x55160A33, 0x22200A3A, 0x99140826});
    }
}
