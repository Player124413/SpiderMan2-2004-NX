package com.spiderman2.android;

import android.app.Activity;
import android.graphics.Color;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.TextView;

/**
 * Java-only screen for the GitHub Actions packaging check. The real SDL activity
 * remains in src/main and is compiled whenever mockBuild is not enabled.
 */
public final class GameActivity extends Activity {
    private TouchOverlayView controls;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        immersive();
        configureMock();

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.rgb(11, 18, 29));

        TextView notice = new TextView(this);
        notice.setText("WORKFLOW MOCK APK\n\nPackaging test passed.\nNo game files, XBE, generated code, SDL, or native game runtime are present.");
        notice.setTextColor(Color.WHITE);
        notice.setTextSize(17);
        notice.setGravity(Gravity.CENTER);
        notice.setPadding(dp(32), dp(32), dp(32), dp(32));
        root.addView(notice, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        controls = new TouchOverlayView(this);
        root.addView(controls, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        setContentView(root);
    }

    @Override protected void onResume() {
        super.onResume();
        immersive();
        NativeBridge.nativeSetForeground(true);
        if (controls != null) controls.reloadPreferences();
    }

    @Override protected void onPause() {
        if (controls != null) controls.releaseAll();
        NativeBridge.nativeSetForeground(false);
        super.onPause();
    }

    @Override public void onBackPressed() {
        NativeBridge.nativeSetButton(NativeBridge.BACK, true);
        NativeBridge.nativeSetButton(NativeBridge.BACK, false);
    }

    private void configureMock() {
        android.content.SharedPreferences prefs = PortPreferences.get(this);
        NativeBridge.nativeConfigure(
                prefs.getString(PortPreferences.GAME_DIR, PortPreferences.defaultGameDirectory()),
                prefs.getInt(PortPreferences.RENDER, 67),
                prefs.getInt(PortPreferences.FPS, 60),
                prefs.getBoolean(PortPreferences.VSYNC, true),
                prefs.getInt(PortPreferences.ANISO, 4));
    }

    @SuppressWarnings("deprecation")
    private void immersive() {
        getWindow().setStatusBarColor(Color.BLACK);
        getWindow().setNavigationBarColor(Color.BLACK);
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION |
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN |
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
