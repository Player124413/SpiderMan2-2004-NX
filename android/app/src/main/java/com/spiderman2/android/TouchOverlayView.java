package com.spiderman2.android;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.view.MotionEvent;
import android.view.View;

import java.util.HashMap;
import java.util.Map;

/**
 * Low-latency, multi-touch overlay. It is deliberately a View rather than an
 * accessibility overlay: it follows the SDL surface pixel-for-pixel, survives rotation,
 * and sends only normalised controller values to the native input bridge.
 */
public final class TouchOverlayView extends View {
    private static final int RED = Color.rgb(211, 34, 54);
    private static final int BLUE = Color.rgb(25, 104, 170);
    private static final int DARK = Color.rgb(11, 18, 29);

    private final SharedPreferences prefs;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Map<Integer, ControlLayout.Id> pointers = new HashMap<>();
    private final Vibrator vibrator;

    private ControlLayout layout;
    private boolean editing;
    private ControlLayout.Id selected;
    private float pinchDistance;
    private float pinchScale;
    private float opacity;
    private float overallSize;
    private boolean editGesturePending;
    private final Runnable editGesture = () -> {
        if (!editing && editGesturePending) {
            editGesturePending = false;
            beginEdit();
        }
    };

    public TouchOverlayView(Context context) {
        super(context);
        prefs = PortPreferences.get(context);
        layout = ControlLayout.load(prefs);
        opacity = prefs.getInt(PortPreferences.CONTROL_OPACITY, 62) / 100f;
        overallSize = prefs.getInt(PortPreferences.CONTROL_SIZE, 100) / 100f;
        vibrator = context.getSystemService(Vibrator.class);
        text.setTypeface(android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL));
        setFocusable(true);
        setContentDescription("Editable touch controls");
    }

    public void reloadPreferences() {
        layout = ControlLayout.load(prefs);
        opacity = prefs.getInt(PortPreferences.CONTROL_OPACITY, 62) / 100f;
        overallSize = prefs.getInt(PortPreferences.CONTROL_SIZE, 100) / 100f;
        invalidate();
    }

    public void releaseAll() {
        cancelEditGesture();
        pointers.clear();
        NativeBridge.nativeReleaseTouch();
        invalidate();
    }

    public boolean isEditing() { return editing; }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (getWidth() == 0 || getHeight() == 0) return;
        for (ControlLayout.Id id : ControlLayout.Id.values()) {
            ControlLayout.Entry entry = layout.get(id);
            drawControl(canvas, id, entry, selected == id && editing);
        }
        if (editing) drawEditor(canvas);
    }

    private void drawControl(Canvas canvas, ControlLayout.Id id, ControlLayout.Entry entry, boolean highlighted) {
        float cx = entry.x * getWidth();
        float cy = entry.y * getHeight();
        float radius = radius(id, entry);
        int alpha = Math.round((editing ? .88f : opacity) * 255);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(id.stick ? BLUE : RED);
        paint.setAlpha(alpha);
        if (id.stick) {
            canvas.drawCircle(cx, cy, radius, paint);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(dp(2));
            paint.setColor(Color.WHITE);
            paint.setAlpha(Math.min(220, alpha + 45));
            canvas.drawCircle(cx, cy, radius * .52f, paint);
            canvas.drawLine(cx - radius * .7f, cy, cx + radius * .7f, cy, paint);
            canvas.drawLine(cx, cy - radius * .7f, cx, cy + radius * .7f, paint);
        } else if (isPill(id)) {
            RectF pill = new RectF(cx - radius * 1.35f, cy - radius * .62f, cx + radius * 1.35f, cy + radius * .62f);
            canvas.drawRoundRect(pill, radius * .48f, radius * .48f, paint);
        } else {
            canvas.drawCircle(cx, cy, radius, paint);
        }
        if (highlighted) {
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(dp(3));
            paint.setColor(Color.WHITE);
            paint.setAlpha(255);
            canvas.drawCircle(cx, cy, radius + dp(5), paint);
        }
        paint.setStyle(Paint.Style.FILL);
        text.setTextAlign(Paint.Align.CENTER);
        text.setTextSize(id.stick ? dp(12) : Math.max(dp(10), radius * .33f));
        text.setColor(Color.WHITE);
        text.setAlpha(245);
        float baseline = cy - (text.descent() + text.ascent()) / 2f;
        canvas.drawText(id.label, cx, baseline, text);
    }

    private void drawEditor(Canvas canvas) {
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(DARK);
        paint.setAlpha(232);
        canvas.drawRect(0, 0, getWidth(), dp(68), paint);
        text.setTextAlign(Paint.Align.LEFT);
        text.setColor(Color.WHITE);
        text.setTextSize(dp(15));
        text.setAlpha(255);
        canvas.drawText("EDIT CONTROLS", dp(18), dp(27), text);
        text.setTextSize(dp(11));
        text.setAlpha(195);
        canvas.drawText("Drag a control · two-finger pinch to resize", dp(18), dp(49), text);
        drawEditorButton(canvas, "RESET", getWidth() - dp(222), false);
        drawEditorButton(canvas, "DONE", getWidth() - dp(108), true);
    }

    private void drawEditorButton(Canvas canvas, String label, float x, boolean primary) {
        float y = dp(16), w = dp(94), h = dp(36);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(primary ? RED : Color.rgb(63, 75, 91));
        paint.setAlpha(255);
        canvas.drawRoundRect(new RectF(x, y, x + w, y + h), dp(8), dp(8), paint);
        text.setTextAlign(Paint.Align.CENTER);
        text.setTextSize(dp(11));
        text.setColor(Color.WHITE);
        text.setAlpha(255);
        canvas.drawText(label, x + w / 2f, y + h / 2f - (text.descent() + text.ascent()) / 2f, text);
    }

    @Override public boolean onTouchEvent(MotionEvent event) {
        final int action = event.getActionMasked();
        final int actionIndex = event.getActionIndex();
        switch (action) {
            case MotionEvent.ACTION_DOWN:
            case MotionEvent.ACTION_POINTER_DOWN:
                down(event, actionIndex);
                break;
            case MotionEvent.ACTION_MOVE:
                if (!editing && event.getPointerCount() < 3) cancelEditGesture();
                move(event);
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_POINTER_UP:
            case MotionEvent.ACTION_CANCEL:
                if (!editing) cancelEditGesture();
                up(event, actionIndex, action == MotionEvent.ACTION_CANCEL);
                break;
            default:
                break;
        }
        return true;
    }

    @Override public boolean performClick() {
        super.performClick();
        return true;
    }

    private void down(MotionEvent event, int index) {
        float x = event.getX(index), y = event.getY(index);
        int pointer = event.getPointerId(index);
        // Three fingers held together enters configuration. Normal play therefore
        // draws only original Xbox controls, without an Android-only EDIT button.
        if (!editing && event.getPointerCount() >= 3) {
            armEditGesture();
            return;
        }
        if (editing) {
            if (pointer == 0 && inEditorAction(x, y)) return;
            ControlLayout.Id hit = findControl(x, y);
            if (hit != null) {
                selected = hit;
                pointers.put(pointer, hit);
                if (pointers.size() == 2) startPinch(event);
                vibrate();
                invalidate();
            }
            return;
        }
        ControlLayout.Id hit = findControl(x, y);
        if (hit == null) return;
        pointers.put(pointer, hit);
        press(hit, true);
        update(hit, x, y);
        vibrate();
        invalidate();
    }

    private void move(MotionEvent event) {
        if (editing) {
            if (selected == null) return;
            if (pointers.size() >= 2) {
                float distance = pointerDistance(event);
                if (pinchDistance > 0) {
                    ControlLayout.Entry entry = layout.get(selected);
                    entry.scale = ControlLayout.clamp(pinchScale * distance / pinchDistance, .50f, 1.75f);
                    invalidate();
                }
                return;
            }
            for (Map.Entry<Integer, ControlLayout.Id> mapped : pointers.entrySet()) {
                int index = event.findPointerIndex(mapped.getKey());
                if (index < 0 || mapped.getValue() != selected) continue;
                ControlLayout.Entry entry = layout.get(selected);
                entry.x = ControlLayout.clamp(event.getX(index) / getWidth(), .03f, .97f);
                entry.y = ControlLayout.clamp(event.getY(index) / getHeight(), .08f, .95f);
                invalidate();
                break;
            }
            return;
        }
        for (Map.Entry<Integer, ControlLayout.Id> mapped : pointers.entrySet()) {
            int index = event.findPointerIndex(mapped.getKey());
            if (index >= 0) update(mapped.getValue(), event.getX(index), event.getY(index));
        }
    }

    private void up(MotionEvent event, int actionIndex, boolean cancel) {
        if (cancel) {
            releaseAll();
            if (editing) pointers.clear();
            return;
        }
        int pointer = event.getPointerId(actionIndex);
        ControlLayout.Id id = pointers.remove(pointer);
        if (editing) {
            pinchDistance = 0;
            if (id != null && pointers.isEmpty()) layout.save(prefs);
            invalidate();
            return;
        }
        if (id != null) {
            press(id, false);
            if (id.stick) NativeBridge.nativeSetStick(id == ControlLayout.Id.LEFT_STICK ? 0 : 1, 0, 0);
        }
        if (pointers.isEmpty()) performClick();
        invalidate();
    }

    private void update(ControlLayout.Id id, float x, float y) {
        if (!id.stick) return;
        ControlLayout.Entry entry = layout.get(id);
        float cx = entry.x * getWidth(), cy = entry.y * getHeight(), range = radius(id, entry) * .82f;
        float dx = x - cx, dy = y - cy;
        float length = (float) Math.hypot(dx, dy);
        if (length > range) { dx = dx / length * range; dy = dy / length * range; }
        NativeBridge.nativeSetStick(id == ControlLayout.Id.LEFT_STICK ? 0 : 1, dx / range, -dy / range);
    }

    private void press(ControlLayout.Id id, boolean down) {
        if (!id.stick && id.nativeButton >= 0) NativeBridge.nativeSetButton(id.nativeButton, down);
    }

    private void beginEdit() {
        releaseAll();
        editing = true;
        selected = null;
        invalidate();
    }

    private boolean inEditorAction(float x, float y) {
        if (y > dp(60)) return false;
        if (x >= getWidth() - dp(108)) {
            editing = false;
            selected = null;
            layout.save(prefs);
            invalidate();
            return true;
        }
        if (x >= getWidth() - dp(222)) {
            String preset = prefs.getString(PortPreferences.CONTROL_PRESET, "balanced");
            layout = ControlLayout.preset(preset);
            selected = null;
            layout.save(prefs);
            invalidate();
            return true;
        }
        return false;
    }

    private void startPinch(MotionEvent event) {
        pinchDistance = pointerDistance(event);
        pinchScale = selected == null ? 1f : layout.get(selected).scale;
    }

    private float pointerDistance(MotionEvent event) {
        if (event.getPointerCount() < 2) return 0;
        float dx = event.getX(0) - event.getX(1);
        float dy = event.getY(0) - event.getY(1);
        return Math.max(1f, (float) Math.hypot(dx, dy));
    }

    private ControlLayout.Id findControl(float x, float y) {
        ControlLayout.Id nearest = null;
        float best = Float.MAX_VALUE;
        for (ControlLayout.Id id : ControlLayout.Id.values()) {
            ControlLayout.Entry entry = layout.get(id);
            float dx = x - entry.x * getWidth(), dy = y - entry.y * getHeight();
            float scaleX = isPill(id) ? 1.35f : 1f;
            float normalized = (dx * dx) / (scaleX * scaleX) + dy * dy;
            float hit = radius(id, entry) * 1.18f;
            if (normalized <= hit * hit && normalized < best) { best = normalized; nearest = id; }
        }
        return nearest;
    }

    private boolean isPill(ControlLayout.Id id) {
        return id == ControlLayout.Id.LEFT_TRIGGER || id == ControlLayout.Id.RIGHT_TRIGGER ||
                id == ControlLayout.Id.BLACK || id == ControlLayout.Id.WHITE;
    }

    private void armEditGesture() {
        if (editGesturePending) return;
        releaseAll();
        editGesturePending = true;
        postDelayed(editGesture, 550);
    }

    private void cancelEditGesture() {
        if (!editGesturePending) return;
        editGesturePending = false;
        removeCallbacks(editGesture);
    }

    private float radius(ControlLayout.Id id, ControlLayout.Entry entry) {
        return dp(id.stick ? 58 : 38) * entry.scale * overallSize;
    }

    private float dp(float value) { return value * getResources().getDisplayMetrics().density; }

    private void vibrate() {
        if (vibrator == null || !vibrator.hasVibrator()) return;
        vibrator.vibrate(VibrationEffect.createOneShot(8, VibrationEffect.DEFAULT_AMPLITUDE));
    }
}
