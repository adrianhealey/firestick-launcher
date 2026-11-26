package com.adrianhealey.tvhome;

import android.graphics.drawable.GradientDrawable;
import android.os.Build;

/**
 * GTA VI "Vice City" look. Applied on both the NVIDIA Shield and the Amazon Fire Stick.
 * Everything here is gated behind {@link #GTA}.
 */
final class Theme {
    private Theme() { }

    /** True on all devices — both the Shield and the Fire Stick use the Vice City theme. */
    static final boolean GTA = true;

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

    // App-tile backgrounds: neon pairs, all settling into a deep indigo so the set reads as one.
    private static final int[][] TILES = {
            {0xFFFF2E9A, 0xFF3A1466},   // pink -> indigo
            {0xFF17D9D9, 0xFF2A0F4E},   // cyan -> indigo
            {0xFFFF6A3C, 0xFF7A1FA2},   // orange -> purple
            {0xFFB54CFF, 0xFF2A0F4E},   // violet -> indigo
    };

    /** A neon gradient for an app tile, chosen deterministically from the package name. */
    static int[] tileGradient(String key) {
        int i = Math.floorMod(key == null ? 0 : key.hashCode(), TILES.length);
        return TILES[i].clone();
    }

    static final int TILE_EDGE = 0x66FF2E9A;   // soft pink edge around each tile
}
