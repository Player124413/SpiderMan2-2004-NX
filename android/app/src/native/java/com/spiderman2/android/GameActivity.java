package com.spiderman2.android;

import android.os.Bundle;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.ViewGroup;

import org.libsdl.app.SDLActivity;

/** SDL surface plus a Java touch layer. The overlay talks directly to port 0 and never simulates mouse events. */
public final class GameActivity extends SDLActivity {
    private TouchOverlayView controls;

    @Override protected String[] getLibraries() {
        return new String[] {"SDL3", "main"};
    }

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        immersive();
        PortPreferences.get(this); // Make the ownership of settings explicit before native main reads them.
        configureNative();
        controls = new TouchOverlayView(this);
        if (mLayout != null) {
            ViewGroup.LayoutParams layout = new ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
            mLayout.addView(controls, layout);
        }
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
        // Android Back opens the game pause menu through the same Xbox Back binding;
        // it never terminates a running session by accident.
        NativeBridge.nativeSetButton(NativeBridge.BACK, true);
        NativeBridge.nativeSetButton(NativeBridge.BACK, false);
    }

    private void configureNative() {
        android.content.SharedPreferences prefs = PortPreferences.get(this);
        NativeBridge.nativeConfigure(
                prefs.getString(PortPreferences.GAME_DIR, PortPreferences.defaultGameDirectory()),
                prefs.getInt(PortPreferences.RENDER, 67),
                prefs.getInt(PortPreferences.FPS, 60),
                prefs.getBoolean(PortPreferences.VSYNC, true),
                prefs.getInt(PortPreferences.ANISO, 4));
    }

    private void immersive() {
        getWindow().setStatusBarColor(android.graphics.Color.BLACK);
        getWindow().setNavigationBarColor(android.graphics.Color.BLACK);
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            WindowInsetsController controller = getWindow().getInsetsController();
            if (controller != null) controller.hide(WindowInsets.Type.statusBars() | WindowInsets.Type.navigationBars());
        } else {
            getWindow().getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION |
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN |
                    View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
        }
    }
}
