package com.adrianhealey.tvhome;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.text.InputType;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

/**
 * Full-screen WebView for the Home Assistant cameras web app (the page used on
 * the glasses). It needs a Home Assistant access token, which the page reads
 * from "#token=..." in the URL. The token is entered once here on the Fire Stick
 * and saved only on this device (never in the app's public code).
 */
public class CameraActivity extends Activity {

    private static final String PREFS = "cameras";
    private static final String KEY_TOKEN = "token";

    private WebView web;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        String token = prefs().getString(KEY_TOKEN, "");
        if (TextUtils.isEmpty(token)) showTokenEntry();
        else showCameras(token);
    }

    private SharedPreferences prefs() {
        return getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private int dp(float v) { return Tiles.dp(this, v); }

    // ------------------------------------------------------ token entry screen

    private void showTokenEntry() {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER);
        col.setBackgroundColor(0xFF111418);
        int pad = dp(48);
        col.setPadding(pad, pad, pad, pad);

        TextView title = new TextView(this);
        title.setText("Connect to Home Assistant");
        title.setTextColor(Color.WHITE);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 26);
        title.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
        col.addView(title);

        TextView sub = new TextView(this);
        sub.setText("Paste a Home Assistant long-lived access token. It is saved only on this Fire Stick.");
        sub.setTextColor(0xCCEBEBF5);
        sub.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        sub.setPadding(0, dp(10), 0, dp(22));
        sub.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams subLp = new LinearLayout.LayoutParams(dp(620), ViewGroup.LayoutParams.WRAP_CONTENT);
        col.addView(sub, subLp);

        final EditText field = new EditText(this);
        field.setHint("Access token");
        field.setHintTextColor(0x80FFFFFF);
        field.setTextColor(Color.WHITE);
        field.setSingleLine(true);
        field.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD);
        field.setText(prefs().getString(KEY_TOKEN, ""));
        GradientDrawable fb = new GradientDrawable();
        fb.setCornerRadius(dp(8));
        fb.setColor(0xFF1E242C);
        fb.setStroke(dp(1), 0x40FFFFFF);
        field.setBackground(fb);
        field.setPadding(dp(16), dp(14), dp(16), dp(14));
        LinearLayout.LayoutParams fieldLp = new LinearLayout.LayoutParams(dp(620), ViewGroup.LayoutParams.WRAP_CONTENT);
        col.addView(field, fieldLp);

        final Button save = new Button(this);
        save.setText("Save and connect");
        save.setAllCaps(false);
        LinearLayout.LayoutParams saveLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        saveLp.topMargin = dp(22);
        col.addView(save, saveLp);

        save.setOnClickListener(v -> {
            String t = field.getText().toString().trim();
            if (TextUtils.isEmpty(t)) {
                Toast.makeText(this, "Paste a token first", Toast.LENGTH_SHORT).show();
                return;
            }
            prefs().edit().putString(KEY_TOKEN, t).apply();
            showCameras(t);
        });

        setContentView(col);
        field.requestFocus();
    }

    // ------------------------------------------------------------- cameras

    private void showCameras(String token) {
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);

        web = new WebView(this);
        web.setBackgroundColor(Color.BLACK);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);  // let the camera feeds auto-play
        s.setUseWideViewPort(true);
        s.setLoadWithOverviewMode(true);               // fit the page to the TV width
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE);
        s.setCacheMode(WebSettings.LOAD_DEFAULT);
        web.setWebViewClient(new WebViewClient());
        web.setWebChromeClient(new WebChromeClient());
        setContentView(web);

        web.loadUrl(AppRepo.CAMERAS_URL + "#token=" + Uri.encode(token));
    }

    // ---------------------------------------------------------------- keys

    @Override
    public boolean onKeyDown(int code, KeyEvent e) {
        if (code == KeyEvent.KEYCODE_MENU) {
            // Menu re-opens the token screen, so the token can be changed later.
            if (web != null) { web.destroy(); web = null; }
            showTokenEntry();
            return true;
        }
        if (code == KeyEvent.KEYCODE_BACK && web != null && web.canGoBack()) {
            web.goBack();
            return true;
        }
        return super.onKeyDown(code, e);
    }

    @Override
    protected void onDestroy() {
        if (web != null) {
            web.destroy();
            web = null;
        }
        super.onDestroy();
    }
}
