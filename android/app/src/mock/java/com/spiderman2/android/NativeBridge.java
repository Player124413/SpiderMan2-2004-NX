package com.spiderman2.android;

/**
 * Build-only replacement for the JNI bridge. It is selected exclusively with
 * -PmockBuild=true and must never be used for a player-owned native game build.
 */
public final class NativeBridge {
    private NativeBridge() {}

    public static final int A = 0;
    public static final int B = 1;
    public static final int X = 2;
    public static final int Y = 3;
    public static final int BLACK = 4;
    public static final int WHITE = 5;
    public static final int LEFT_TRIGGER = 6;
    public static final int RIGHT_TRIGGER = 7;
    public static final int DPAD_UP = 8;
    public static final int DPAD_DOWN = 9;
    public static final int DPAD_LEFT = 10;
    public static final int DPAD_RIGHT = 11;
    public static final int START = 12;
    public static final int BACK = 13;
    public static final int LEFT_STICK_CLICK = 14;
    public static final int RIGHT_STICK_CLICK = 15;

    private static volatile String configuration = "No game data";

    public static void nativeConfigure(String gameDirectory, int renderPercent, int fpsCap, boolean vsync, int aniso) {
        configuration = "Mock only · " + renderPercent + "% · " + (fpsCap == 0 ? "unlimited" : fpsCap + " FPS");
    }

    public static void nativeSetButton(int id, boolean down) { }
    public static void nativeSetStick(int stick, float x, float y) { }
    public static void nativeReleaseTouch() { }
    public static void nativeSetForeground(boolean foreground) { }

    public static String nativeRuntimeSummary() {
        return "Workflow mock APK · no native game runtime · " + configuration;
    }
}
