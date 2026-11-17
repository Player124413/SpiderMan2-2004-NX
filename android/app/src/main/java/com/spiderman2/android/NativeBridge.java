package com.spiderman2.android;

/** Thread-safe bridge to the native port. IDs match android/native/android_input.cpp. */
public final class NativeBridge {
    private NativeBridge() {}

    public static final int A = 0;
    public static final int B = 1;
    public static final int X = 2;
    public static final int Y = 3;
    public static final int BLACK = 4;       // right bumper
    public static final int WHITE = 5;       // left bumper
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

    public static native void nativeConfigure(String gameDirectory, int renderPercent, int fpsCap, boolean vsync, int aniso);
    public static native void nativeSetButton(int id, boolean down);
    public static native void nativeSetStick(int stick, float x, float y);
    public static native void nativeReleaseTouch();
    public static native void nativeSetForeground(boolean foreground);
    public static native String nativeRuntimeSummary();
}
