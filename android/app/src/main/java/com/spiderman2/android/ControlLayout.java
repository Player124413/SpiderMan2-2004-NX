package com.spiderman2.android;

import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.EnumMap;
import java.util.Locale;

/** Persisted landscape controls. Positions are normalized, so one layout works on every aspect ratio. */
final class ControlLayout {
    enum Id {
        MOVE("MOVE", -1, true),
        LOOK("LOOK", -1, true),
        A("JUMP", NativeBridge.A, false),
        B("ATTACK", NativeBridge.B, false),
        X("WEB", NativeBridge.X, false),
        Y("ACTION", NativeBridge.Y, false),
        RT("WEB", NativeBridge.RIGHT_TRIGGER, false),
        LT("LOCK", NativeBridge.LEFT_TRIGGER, false),
        RB("DODGE", NativeBridge.BLACK, false),
        LB("CAMERA", NativeBridge.WHITE, false),
        START("PAUSE", NativeBridge.START, false),
        BACK("MAP", NativeBridge.BACK, false);

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
        boolean visible = true;
        Entry(float x, float y, float scale) { this.x = x; this.y = y; this.scale = scale; }
        Entry copy() { Entry entry = new Entry(x, y, scale); entry.visible = visible; return entry; }
    }

    private final EnumMap<Id, Entry> entries = new EnumMap<>(Id.class);

    private ControlLayout() {}
    Entry get(Id id) { return entries.get(id); }

    static ControlLayout preset(String preset) {
        ControlLayout layout = new ControlLayout();
        // Balanced: all primary actions can be reached without covering the picture's centre.
        layout.entries.put(Id.MOVE, new Entry(.16f, .75f, 1.00f));
        layout.entries.put(Id.LOOK, new Entry(.72f, .74f, .88f));
        layout.entries.put(Id.A, new Entry(.88f, .68f, .90f));
        layout.entries.put(Id.B, new Entry(.80f, .79f, .90f));
        layout.entries.put(Id.X, new Entry(.80f, .58f, .82f));
        layout.entries.put(Id.Y, new Entry(.94f, .79f, .82f));
        layout.entries.put(Id.RT, new Entry(.91f, .22f, .92f));
        layout.entries.put(Id.LT, new Entry(.08f, .22f, .80f));
        layout.entries.put(Id.RB, new Entry(.78f, .29f, .72f));
        layout.entries.put(Id.LB, new Entry(.19f, .28f, .70f));
        layout.entries.put(Id.START, new Entry(.54f, .12f, .58f));
        layout.entries.put(Id.BACK, new Entry(.46f, .12f, .58f));
        if ("compact".equals(preset)) {
            layout.get(Id.MOVE).scale = .88f;
            layout.get(Id.LOOK).scale = .75f;
            for (Id id : Id.values()) if (!id.stick) layout.get(id).scale *= .82f;
        } else if ("claw".equals(preset)) {
            layout.get(Id.RT).x = .96f;
            layout.get(Id.RT).y = .14f;
            layout.get(Id.LT).x = .04f;
            layout.get(Id.LT).y = .14f;
            layout.get(Id.RB).x = .84f;
            layout.get(Id.RB).y = .23f;
            layout.get(Id.LB).x = .15f;
            layout.get(Id.LB).y = .23f;
            layout.get(Id.LOOK).x = .69f;
            layout.get(Id.LOOK).y = .80f;
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
                entry.visible = item.optBoolean("v", entry.visible);
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
                item.put("v", entry.visible);
                root.put(id.name(), item);
            }
        } catch (JSONException ignored) {
            return;
        }
        prefs.edit().putString(PortPreferences.CONTROL_LAYOUT, root.toString()).apply();
    }

    ControlLayout copy() {
        ControlLayout copy = new ControlLayout();
        for (Id id : Id.values()) copy.entries.put(id, get(id).copy());
        return copy;
    }

    static float clamp(float value, float min, float max) { return Math.max(min, Math.min(max, value)); }
}
