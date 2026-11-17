package com.spiderman2.android;

import android.content.SharedPreferences;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.EnumMap;

/**
 * Persisted layout for the original Xbox controller only. Every visible item maps
 * one-to-one to an Xbox input; no gameplay macro or Android-only action is added.
 */
final class ControlLayout {
    enum Id {
        LEFT_STICK("L", -1, true),
        RIGHT_STICK("R", -1, true),
        DPAD_UP("↑", NativeBridge.DPAD_UP, false),
        DPAD_DOWN("↓", NativeBridge.DPAD_DOWN, false),
        DPAD_LEFT("←", NativeBridge.DPAD_LEFT, false),
        DPAD_RIGHT("→", NativeBridge.DPAD_RIGHT, false),
        A("A", NativeBridge.A, false),
        B("B", NativeBridge.B, false),
        X("X", NativeBridge.X, false),
        Y("Y", NativeBridge.Y, false),
        BLACK("BLACK", NativeBridge.BLACK, false),
        WHITE("WHITE", NativeBridge.WHITE, false),
        LEFT_TRIGGER("LT", NativeBridge.LEFT_TRIGGER, false),
        RIGHT_TRIGGER("RT", NativeBridge.RIGHT_TRIGGER, false),
        START("START", NativeBridge.START, false),
        BACK("BACK", NativeBridge.BACK, false);

        final String label;
        final int nativeButton;
        final boolean stick;
        Id(String label, int nativeButton, boolean stick) {
            this.label = label;
            this.nativeButton = nativeButton;
            this.stick = stick;
        }
    }

    static final class Entry {
        float x;
        float y;
        float scale;
        Entry(float x, float y, float scale) { this.x = x; this.y = y; this.scale = scale; }
    }

    private final EnumMap<Id, Entry> entries = new EnumMap<>(Id.class);

    private ControlLayout() {}
    Entry get(Id id) { return entries.get(id); }

    static ControlLayout preset(String preset) {
        ControlLayout layout = new ControlLayout();
        // The physical Xbox arrangement: stick + D-pad on the left, face buttons
        // on the right, black/white above them, triggers at the upper edges.
        layout.entries.put(Id.LEFT_STICK, new Entry(.15f, .74f, 1.00f));
        layout.entries.put(Id.RIGHT_STICK, new Entry(.65f, .74f, .88f));
        layout.entries.put(Id.DPAD_UP, new Entry(.35f, .64f, .52f));
        layout.entries.put(Id.DPAD_DOWN, new Entry(.35f, .82f, .52f));
        layout.entries.put(Id.DPAD_LEFT, new Entry(.29f, .73f, .52f));
        layout.entries.put(Id.DPAD_RIGHT, new Entry(.41f, .73f, .52f));
        layout.entries.put(Id.A, new Entry(.89f, .68f, .90f));
        layout.entries.put(Id.B, new Entry(.81f, .79f, .90f));
        layout.entries.put(Id.X, new Entry(.81f, .58f, .82f));
        layout.entries.put(Id.Y, new Entry(.95f, .79f, .82f));
        layout.entries.put(Id.BLACK, new Entry(.77f, .31f, .60f));
        layout.entries.put(Id.WHITE, new Entry(.24f, .31f, .60f));
        layout.entries.put(Id.RIGHT_TRIGGER, new Entry(.92f, .18f, .86f));
        layout.entries.put(Id.LEFT_TRIGGER, new Entry(.08f, .18f, .86f));
        layout.entries.put(Id.START, new Entry(.55f, .12f, .54f));
        layout.entries.put(Id.BACK, new Entry(.46f, .12f, .54f));
        if ("compact".equals(preset)) {
            layout.get(Id.LEFT_STICK).scale = .88f;
            layout.get(Id.RIGHT_STICK).scale = .76f;
            for (Id id : Id.values()) if (!id.stick) layout.get(id).scale *= .80f;
        } else if ("claw".equals(preset)) {
            layout.get(Id.RIGHT_TRIGGER).x = .96f;
            layout.get(Id.RIGHT_TRIGGER).y = .13f;
            layout.get(Id.LEFT_TRIGGER).x = .04f;
            layout.get(Id.LEFT_TRIGGER).y = .13f;
            layout.get(Id.BLACK).x = .84f;
            layout.get(Id.BLACK).y = .25f;
            layout.get(Id.WHITE).x = .16f;
            layout.get(Id.WHITE).y = .25f;
            layout.get(Id.RIGHT_STICK).x = .67f;
            layout.get(Id.RIGHT_STICK).y = .80f;
        }
        return layout;
    }

    static ControlLayout load(SharedPreferences prefs) {
        String saved = prefs.getString(PortPreferences.CONTROL_LAYOUT, null);
        String preset = prefs.getString(PortPreferences.CONTROL_PRESET, "balanced");
        if (saved == null || saved.isEmpty()) return preset(preset);
        try {
            JSONObject root = new JSONObject(saved);
            ControlLayout layout = preset(preset);
            for (Id id : Id.values()) {
                JSONObject item = root.optJSONObject(id.name());
                if (item == null) continue;
                Entry entry = layout.get(id);
                entry.x = clamp((float) item.optDouble("x", entry.x), .03f, .97f);
                entry.y = clamp((float) item.optDouble("y", entry.y), .05f, .95f);
                entry.scale = clamp((float) item.optDouble("s", entry.scale), .50f, 1.75f);
            }
            return layout;
        } catch (JSONException ignored) {
            return preset(preset);
        }
    }

    void save(SharedPreferences prefs) {
        JSONObject root = new JSONObject();
        try {
            for (Id id : Id.values()) {
                Entry entry = get(id);
                JSONObject item = new JSONObject();
                item.put("x", entry.x);
                item.put("y", entry.y);
                item.put("s", entry.scale);
                root.put(id.name(), item);
            }
        } catch (JSONException ignored) {
            return;
        }
        prefs.edit().putString(PortPreferences.CONTROL_LAYOUT, root.toString()).apply();
    }

    static float clamp(float value, float min, float max) { return Math.max(min, Math.min(max, value)); }
}
