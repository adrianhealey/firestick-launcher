package com.adrianhealey.tvhome;

import android.accessibilityservice.AccessibilityService;
import android.content.Intent;
import android.os.SystemClock;
import android.view.accessibility.AccessibilityEvent;

/**
 * Fire OS always returns to Amazon's launcher when Home is pressed.
 * This service sees Amazon's home screen come to the front and opens
 * TV Home over it straight away.
 */
public class HomeWatcherService extends AccessibilityService {

    private static final String AMAZON_LAUNCHER = "com.amazon.tv.launcher";
    private long lastLaunch;

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null || event.getEventType() != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return;
        CharSequence pkg = event.getPackageName();
        if (pkg == null || !AMAZON_LAUNCHER.contentEquals(pkg)) return;
        if (!Prefs.watcherOn(this) || Prefs.watcherPaused(this)) return;

        // Amazon's own settings/profile screens live in other classes; only
        // replace the main home screen.
        CharSequence cls = event.getClassName();
        if (cls != null) {
            String c = cls.toString();
            if (c.contains("Settings") || c.contains("Profile") || c.contains("Oobe")) return;
        }

        long now = SystemClock.elapsedRealtime();
        if (now - lastLaunch < 400) return;
        lastLaunch = now;

        Intent i = new Intent(this, MainActivity.class);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP
                | Intent.FLAG_ACTIVITY_NO_ANIMATION);
        startActivity(i);
    }

    @Override
    public void onInterrupt() { }
}
