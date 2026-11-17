package com.spiderman2.android;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.Settings;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;

/** Launcher, storage preflight and device-safe rendering defaults. */
public final class LauncherActivity extends Activity {
    private static final int REQUEST_LEGACY_STORAGE = 71;
    private static final int RED = Color.rgb(211, 38, 56);
    private static final int INK = Color.rgb(17, 24, 35);

    private SharedPreferences prefs;
    private EditText gameDirectory;
    private TextView status;
    private TextView storageHint;
    private Button play;
    private Spinner render;
    private Spinner fps;
    private Spinner aniso;
    private Switch vsync;
    private Spinner preset;
    private SeekBar opacity;
    private SeekBar size;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        prefs = PortPreferences.get(this);
        setContentView(buildContent());
        restoreSettings();
    }

    @Override protected void onResume() {
        super.onResume();
        checkGame();
    }

    private View buildContent() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(22), dp(20), dp(28));
        root.setBackgroundColor(INK);
        scroll.addView(root);

        TextView title = label("SPIDER-MAN 2", 30, Color.WHITE);
        title.setLetterSpacing(.08f);
        root.addView(title);
        TextView sub = label("ANDROID · NATIVE ARM64 RECOMPILATION", 12, Color.rgb(174, 192, 213));
        sub.setPadding(0, dp(3), 0, dp(22));
        root.addView(sub);

        root.addView(section("GAME FILES"));
        TextView info = label("Use files from your own original Xbox disc. This app never includes game assets.", 14, Color.LTGRAY);
        info.setPadding(0, 0, 0, dp(12));
        root.addView(info);
        gameDirectory = new EditText(this);
        gameDirectory.setSingleLine(true);
        gameDirectory.setTextColor(Color.WHITE);
        gameDirectory.setTextSize(14);
        gameDirectory.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        gameDirectory.setHintTextColor(Color.GRAY);
        gameDirectory.setHint("/storage/emulated/0/SpiderMan2Xbox");
        gameDirectory.setBackgroundColor(Color.rgb(39, 50, 66));
        gameDirectory.setPadding(dp(12), 0, dp(12), 0);
        root.addView(gameDirectory, match(dp(48)));
        storageHint = label("", 13, Color.rgb(174, 192, 213));
        storageHint.setPadding(0, dp(8), 0, dp(10));
        root.addView(storageHint);
        Button storage = secondaryButton("ALLOW GAME FOLDER ACCESS");
        storage.setOnClickListener(v -> requestStorageAccess());
        root.addView(storage, match(dp(44)));

        root.addView(section("PERFORMANCE"));
        render = settingSpinner(root, "Render resolution", new String[] {"50% · Battery saver", "67% · Balanced", "85% · Quality", "100% · Native", "125% · High-end"});
        fps = settingSpinner(root, "Frame limit", new String[] {"30 FPS", "60 FPS", "90 FPS", "120 FPS", "Unlimited"});
        aniso = settingSpinner(root, "Texture filtering", new String[] {"Auto", "2×", "4×", "8×", "16×"});
        vsync = new Switch(this);
        vsync.setText("V-Sync");
        vsync.setTextColor(Color.WHITE);
        vsync.setTextSize(15);
        vsync.setPadding(0, dp(4), 0, dp(14));
        root.addView(vsync, match(dp(52)));

        root.addView(section("TOUCH CONTROLS"));
        TextView controlText = label("Original Xbox controls only. Hold three fingers for 0.5 seconds in-game to edit; drag a control, then pinch with a second finger to resize it.", 14, Color.LTGRAY);
        controlText.setPadding(0, 0, 0, dp(10));
        root.addView(controlText);
        preset = settingSpinner(root, "Starting layout", new String[] {"Balanced", "Compact", "Four-finger claw"});
        opacity = settingSlider(root, "Control opacity", 30, 100);
        size = settingSlider(root, "Overall control size", 70, 135);

        status = label("", 14, Color.WHITE);
        status.setGravity(Gravity.CENTER_VERTICAL);
        status.setPadding(dp(14), dp(10), dp(14), dp(10));
        status.setBackgroundColor(Color.rgb(32, 45, 61));
        LinearLayout.LayoutParams statusParams = match(dp(66));
        statusParams.topMargin = dp(16);
        root.addView(status, statusParams);
        play = new Button(this);
        play.setText("PLAY");
        play.setTextColor(Color.WHITE);
        play.setTextSize(16);
        play.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        play.setBackgroundColor(RED);
        LinearLayout.LayoutParams playParams = match(dp(52));
        playParams.topMargin = dp(12);
        root.addView(play, playParams);
        play.setOnClickListener(v -> launch());
        return scroll;
    }

    private void restoreSettings() {
        gameDirectory.setText(prefs.getString(PortPreferences.GAME_DIR, PortPreferences.defaultGameDirectory()));
        render.setSelection(indexOf(new int[] {50, 67, 85, 100, 125}, prefs.getInt(PortPreferences.RENDER, 67)));
        fps.setSelection(indexOf(new int[] {30, 60, 90, 120, 0}, prefs.getInt(PortPreferences.FPS, 60)));
        aniso.setSelection(indexOf(new int[] {0, 2, 4, 8, 16}, prefs.getInt(PortPreferences.ANISO, 4)));
        vsync.setChecked(prefs.getBoolean(PortPreferences.VSYNC, true));
        String layout = prefs.getString(PortPreferences.CONTROL_PRESET, "balanced");
        preset.setSelection("compact".equals(layout) ? 1 : "claw".equals(layout) ? 2 : 0);
        opacity.setProgress(prefs.getInt(PortPreferences.CONTROL_OPACITY, 62) - 30);
        size.setProgress(prefs.getInt(PortPreferences.CONTROL_SIZE, 100) - 70);
    }

    private void checkGame() {
        boolean access = hasStorageAccess();
        String directory = gameDirectory == null ? "" : gameDirectory.getText().toString().trim();
        File root = new File(directory);
        File xbe = new File(root, "default.xbe");
        File data = new File(root, "data");
        if (!data.isDirectory()) data = new File(root, "DATA");
        if (!access) {
            storageHint.setText("Storage access is needed to read the extracted game folder.");
            status.setText("Waiting for storage permission");
            status.setTextColor(Color.rgb(255, 195, 83));
            play.setEnabled(false);
            return;
        }
        storageHint.setText("Expected: " + root.getAbsolutePath() + "/default.xbe and /data/");
        if (xbe.isFile() && data.isDirectory()) {
            status.setText("Game files ready · Vulkan 1.1 required · ARM64 build");
            status.setTextColor(Color.rgb(128, 230, 159));
            play.setEnabled(true);
        } else {
            status.setText("Game files not ready: copy default.xbe and the complete data folder.");
            status.setTextColor(Color.rgb(255, 195, 83));
            play.setEnabled(false);
        }
    }

    private void launch() {
        if (!hasStorageAccess()) { requestStorageAccess(); return; }
        String path = gameDirectory.getText().toString().trim();
        if (path.isEmpty()) { Toast.makeText(this, "Choose a game folder first", Toast.LENGTH_SHORT).show(); return; }
        int[] renders = {50, 67, 85, 100, 125};
        int[] limits = {30, 60, 90, 120, 0};
        int[] filters = {0, 2, 4, 8, 16};
        String controlPreset = preset.getSelectedItemPosition() == 1 ? "compact" : preset.getSelectedItemPosition() == 2 ? "claw" : "balanced";
        SharedPreferences.Editor editor = prefs.edit()
                .putString(PortPreferences.GAME_DIR, path)
                .putInt(PortPreferences.RENDER, renders[render.getSelectedItemPosition()])
                .putInt(PortPreferences.FPS, limits[fps.getSelectedItemPosition()])
                .putInt(PortPreferences.ANISO, filters[aniso.getSelectedItemPosition()])
                .putBoolean(PortPreferences.VSYNC, vsync.isChecked())
                .putString(PortPreferences.CONTROL_PRESET, controlPreset)
                .putInt(PortPreferences.CONTROL_OPACITY, 30 + opacity.getProgress())
                .putInt(PortPreferences.CONTROL_SIZE, 70 + size.getProgress());
        // A selected preset is an intentional replacement for a prior custom layout.
        if (!controlPreset.equals(prefs.getString(PortPreferences.CONTROL_PRESET, "balanced")))
            editor.remove(PortPreferences.CONTROL_LAYOUT);
        editor.apply();
        startActivity(new Intent(this, GameActivity.class));
    }

    private void requestStorageAccess() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                Intent intent = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                        Uri.parse("package:" + getPackageName()));
                startActivity(intent);
            } catch (Exception ignored) {
                startActivity(new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION));
            }
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            requestPermissions(new String[] {Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE}, REQUEST_LEGACY_STORAGE);
        }
    }

    private boolean hasStorageAccess() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) return Environment.isExternalStorageManager();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
            return checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED;
        return true;
    }

    private Spinner settingSpinner(LinearLayout root, String title, String[] choices) {
        TextView label = label(title, 14, Color.rgb(190, 205, 224));
        label.setPadding(0, dp(12), 0, dp(2));
        root.addView(label);
        Spinner spinner = new Spinner(this);
        spinner.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, choices));
        spinner.setBackgroundColor(Color.rgb(39, 50, 66));
        root.addView(spinner, match(dp(46)));
        return spinner;
    }

    private SeekBar settingSlider(LinearLayout root, String title, int min, int max) {
        TextView label = label(title + "  " + min + "–" + max + "%", 14, Color.rgb(190, 205, 224));
        label.setPadding(0, dp(12), 0, 0);
        root.addView(label);
        SeekBar seek = new SeekBar(this);
        seek.setMax(max - min);
        seek.setPadding(0, 0, 0, dp(4));
        root.addView(seek, match(dp(42)));
        return seek;
    }

    private TextView section(String value) {
        TextView text = label(value, 13, Color.rgb(237, 91, 105));
        text.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        text.setLetterSpacing(.08f);
        text.setPadding(0, dp(24), 0, dp(8));
        return text;
    }

    private TextView label(String value, int sp, int color) {
        TextView text = new TextView(this);
        text.setText(value);
        text.setTextSize(sp);
        text.setTextColor(color);
        return text;
    }

    private Button secondaryButton(String value) {
        Button button = new Button(this);
        button.setText(value);
        button.setTextSize(12);
        button.setTextColor(Color.WHITE);
        button.setBackgroundColor(Color.rgb(58, 76, 99));
        return button;
    }

    private LinearLayout.LayoutParams match(int height) {
        return new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, height);
    }

    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }

    private int indexOf(int[] values, int wanted) {
        for (int i = 0; i < values.length; i++) if (values[i] == wanted) return i;
        return 0;
    }
}
