package com.spiderman2.android;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Environment;

import java.io.File;

final class PortPreferences {
    private static final String NAME = "spiderman2_android";
    static final String GAME_DIR = "game_dir";
    static final String RENDER = "render";
    static final String FPS = "fps";
    static final String VSYNC = "vsync";
    static final String ANISO = "aniso";
    static final String CONTROL_OPACITY = "control_opacity";
    static final String CONTROL_SIZE = "control_size";
    static final String CONTROL_PRESET = "control_preset";
    static final String CONTROL_LAYOUT = "control_layout";

    private PortPreferences() {}

    static SharedPreferences get(Context context) {
        return context.getSharedPreferences(NAME, Context.MODE_PRIVATE);
    }

    static String defaultGameDirectory() {
        return new File(Environment.getExternalStorageDirectory(), "SpiderMan2Xbox").getAbsolutePath();
    }
}
