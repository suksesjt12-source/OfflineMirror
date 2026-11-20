package com.offlinemirror.app;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

import java.util.HashMap;
import java.util.Map;

/** On-screen virtual gamepad; input leaves the Viewer as Bluetooth HID only. */
public final class GamepadOverlayView extends View {
    public interface Listener {
        void onGamepadState(int buttons, float leftX, float leftY,
                            float rightX, float rightY);
    }

    public interface LayoutChangedListener {
        void onPositionChanged(float offsetX, float offsetY);
    }

    // First ten bits map to standard HID buttons. D-pad bits are encoded as a HID hat switch.
    private static final int BUTTON_A = 1 << 0;
    private static final int BUTTON_B = 1 << 1;
    private static final int BUTTON_X = 1 << 2;
    private static final int BUTTON_Y = 1 << 3;
    private static final int BUTTON_L1 = 1 << 4;
    private static final int BUTTON_R1 = 1 << 5;
    private static final int BUTTON_L2 = 1 << 6;
    private static final int BUTTON_R2 = 1 << 7;
    private static final int BUTTON_SELECT = 1 << 8;
    private static final int BUTTON_START = 1 << 9;
    private static final int BUTTON_DPAD_UP = 1 << 10;
    private static final int BUTTON_DPAD_DOWN = 1 << 11;
    private static final int BUTTON_DPAD_LEFT = 1 << 12;
    private static final int BUTTON_DPAD_RIGHT = 1 << 13;

    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint label = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Map<Integer, Integer> buttonPointers = new HashMap<>();
    private Listener listener;
    private float sensitivity = 1.0f;
    private int layoutPreset = 0;
    private float positionOffsetX, positionOffsetY;
    private boolean layoutEditMode;
    private int movePointer = -1;
    private float moveLastX, moveLastY;
    private LayoutChangedListener layoutChangedListener;

    public void setSensitivity(float value) {
        sensitivity = Math.max(0.5f, Math.min(1.8f, value));
    }

    /** 0 = Classic, 1 = Compact, 2 = Left-handed. */
    public void setLayoutPreset(int preset) {
        layoutPreset = Math.max(0, Math.min(2, preset));
        invalidate();
    }

    private float sizeScale() { return layoutPreset == 1 ? 0.78f : 1.0f; }

    public void setLayoutChangedListener(LayoutChangedListener listener) {
        layoutChangedListener = listener;
    }
    public void setPositionOffset(float x, float y) {
        positionOffsetX = Math.max(-0.18f, Math.min(0.18f, x));
        positionOffsetY = Math.max(-0.16f, Math.min(0.16f, y));
        invalidate();
    }
    public float getPositionOffsetX() { return positionOffsetX; }
    public float getPositionOffsetY() { return positionOffsetY; }
    public void setLayoutEditMode(boolean enabled) {
        layoutEditMode = enabled;
        movePointer = -1;
        if (enabled) releaseAllInputs();
        invalidate();
    }
    private float offsetX() { return getWidth() * positionOffsetX; }
    private float offsetY() { return getHeight() * positionOffsetY; }

    private int buttons;
    private int leftStickPointer = -1;
    private int rightStickPointer = -1;
    private float leftAxisX;
    private float leftAxisY;
    private float rightAxisX;
    private float rightAxisY;
    public GamepadOverlayView(Context context) {
        super(context);
        init();
    }

    public GamepadOverlayView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    private void init() {
        setLayerType(View.LAYER_TYPE_HARDWARE, null);
        fill.setStyle(Paint.Style.FILL);
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeWidth(dp(2));
        label.setColor(0xFFF4F7FC);
        label.setTextAlign(Paint.Align.CENTER);
        label.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
        setClickable(true);
    }

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    public void releaseAllInputs() {
        buttonPointers.clear();
        buttons = 0;
        leftStickPointer = -1;
        rightStickPointer = -1;
        leftAxisX = leftAxisY = rightAxisX = rightAxisY = 0f;
        emitState();
    }

    private float dp(float value) {
        return value * getResources().getDisplayMetrics().density;
    }

    private boolean isLandscapeLayout() {
        return getWidth() > getHeight();
    }

    private float leftStickCenterX() {
        if (layoutPreset == 2) return getWidth() * 0.82f + offsetX();
        return getWidth() * (isLandscapeLayout() ? 0.14f : 0.18f) + offsetX();    }

    private float rightStickCenterX() {
        if (layoutPreset == 2) return getWidth() * (isLandscapeLayout() ? 0.32f : 0.48f) + offsetX();
        return getWidth() * (isLandscapeLayout() ? 0.66f : 0.52f) + offsetX();    }

    private float stickCenterY() {
        return getHeight() * (isLandscapeLayout() ? 0.81f : 0.84f) + offsetY();    }

    private float dpadCenterX() {
        return getWidth() * (layoutPreset == 2 ? 0.82f : (isLandscapeLayout() ? 0.14f : 0.18f)) + offsetX();    }

    private float dpadCenterY() {
        // Keep the D-pad hit area clear of the left analog stick.
        return getHeight() * (isLandscapeLayout() ? 0.44f : 0.48f) + offsetY();    }

    private float dpadRadius() {
        return Math.min(dp(20) * sizeScale(), Math.min(getWidth(), getHeight()) * 0.05f);
    }

    private float dpadOffset() {
        return dpadRadius() * 1.22f;
    }

    private float dpadX(int bit) {
        float center = dpadCenterX();
        if (bit == BUTTON_DPAD_LEFT) return center - dpadOffset();
        if (bit == BUTTON_DPAD_RIGHT) return center + dpadOffset();
        return center;
    }

    private float dpadY(int bit) {
        float center = dpadCenterY();
        if (bit == BUTTON_DPAD_UP) return center - dpadOffset();
        if (bit == BUTTON_DPAD_DOWN) return center + dpadOffset();
        return center;
    }

    private float stickRadius() {
        float shortSide = Math.min(getWidth(), getHeight());
        return Math.min(dp(45) * sizeScale(), shortSide * 0.13f);
    }

    private float actionRadius() {
        return Math.min(dp(21) * sizeScale(), Math.min(getWidth(), getHeight()) * 0.055f);
    }

    private float buttonX(int bit) {
        float x;
        if (bit == BUTTON_A || bit == BUTTON_Y) {
            x = getWidth() * (isLandscapeLayout() ? 0.88f : 0.84f);
        } else if (bit == BUTTON_B) {
            x = getWidth() * 0.94f;
        } else if (bit == BUTTON_X) {
            x = getWidth() * (isLandscapeLayout() ? 0.82f : 0.76f);
        } else if (bit == BUTTON_L1) {
            x = getWidth() * 0.10f;
        } else if (bit == BUTTON_L2) {
            x = getWidth() * 0.27f;
        } else if (bit == BUTTON_SELECT) {
            x = getWidth() * (isLandscapeLayout() ? 0.40f : 0.39f);
        } else if (bit == BUTTON_START) {
            x = getWidth() * (isLandscapeLayout() ? 0.60f : 0.61f);
        } else if (bit == BUTTON_R2) {
            x = getWidth() * 0.73f;
        } else if (bit == BUTTON_R1) {
            x = getWidth() * 0.90f;
        } else {
            x = getWidth() * 0.88f;
        }
        return (layoutPreset == 2 ? getWidth() - x : x) + offsetX();    }

    private float buttonY(int bit) {
        float y;
        if (isLandscapeLayout()) {
            if (bit == BUTTON_Y) y = getHeight() * 0.65f;
            else if (bit == BUTTON_A) y = getHeight() * 0.87f;
            else if (bit == BUTTON_X || bit == BUTTON_B) y = getHeight() * 0.76f;
            else y = getHeight() * 0.10f;
        } else {
            if (bit == BUTTON_Y) y = getHeight() * 0.56f;
            else if (bit == BUTTON_X || bit == BUTTON_B) y = getHeight() * 0.65f;
            else if (bit == BUTTON_A) y = getHeight() * 0.74f;
            else y = getHeight() * 0.08f;
        }
        return y + offsetY();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (getWidth() == 0 || getHeight() == 0) return;

        drawStick(canvas, leftStickCenterX(), stickCenterY(), leftAxisX, leftAxisY, "L");
        drawStick(canvas, rightStickCenterX(), stickCenterY(), rightAxisX, rightAxisY, "R");

        drawDpad(canvas);
        drawAction(canvas, BUTTON_Y, "Y");
        drawAction(canvas, BUTTON_X, "X");
        drawAction(canvas, BUTTON_B, "B");
        drawAction(canvas, BUTTON_A, "A");

        drawPill(canvas, BUTTON_L1, "L1");
        drawPill(canvas, BUTTON_L2, "L2");
        drawPill(canvas, BUTTON_SELECT, "SELECT");
        drawPill(canvas, BUTTON_START, "START");
        drawPill(canvas, BUTTON_R2, "R2");
        drawPill(canvas, BUTTON_R1, "R1");
        if (layoutEditMode) {
            stroke.setColor(0xFF75B9FF);
            stroke.setStrokeWidth(dp(2.5f));
            canvas.drawRoundRect(dp(6), dp(6), getWidth() - dp(6),
                    getHeight() - dp(6), dp(18), dp(18), stroke);
            label.setColor(0xFF8CC8FF);
            drawLabel(canvas, "GESER UNTUK MENGATUR POSISI GAMEPAD",
                    getWidth() / 2f, dp(26), dp(11));
            label.setColor(0xFFF4F7FC);
        }
    }

    private void drawStick(Canvas canvas, float cx, float cy,
                           float axisX, float axisY, String name) {
        float radius = stickRadius();
        fill.setColor(0x61303C50);
        canvas.drawCircle(cx, cy, radius, fill);
        stroke.setColor(0xDCE2EAF5);
        canvas.drawCircle(cx, cy, radius, stroke);
        float knobX = cx + axisX * radius * 0.66f;
        float knobY = cy + axisY * radius * 0.66f;
        fill.setColor(0xA9303C50);
        canvas.drawCircle(knobX, knobY, radius * 0.39f, fill);
        stroke.setColor(0xE7EAF0F7);
        canvas.drawCircle(knobX, knobY, radius * 0.39f, stroke);
        drawLabel(canvas, name, knobX, knobY + dp(5), dp(15));
    }

    private void drawDpad(Canvas canvas) {
        int[] directions = {BUTTON_DPAD_UP, BUTTON_DPAD_DOWN,
                BUTTON_DPAD_LEFT, BUTTON_DPAD_RIGHT};
        String[] symbols = {"↑", "↓", "←", "→"};
        float radius = dpadRadius();
        for (int i = 0; i < directions.length; i++) {
            int bit = directions[i];
            float x = dpadX(bit);
            float y = dpadY(bit);
            fill.setColor((buttons & bit) != 0 ? 0xC94B8CC8 : 0x71303C50);
            canvas.drawCircle(x, y, radius, fill);
            stroke.setColor(0xE7EAF0F7);
            canvas.drawCircle(x, y, radius, stroke);
            drawLabel(canvas, symbols[i], x, y + dp(5), dp(18));
        }
    }

    private void drawAction(Canvas canvas, int bit, String text) {
        float x = buttonX(bit);
        float y = buttonY(bit);
        float radius = actionRadius();
        fill.setColor((buttons & bit) != 0 ? 0xC94B8CC8 : 0x71303C50);
        canvas.drawCircle(x, y, radius, fill);
        stroke.setColor(0xE7EAF0F7);
        canvas.drawCircle(x, y, radius, stroke);
        drawLabel(canvas, text, x, y + dp(5), dp(14));
    }

    private void drawPill(Canvas canvas, int bit, String text) {
        float x = buttonX(bit);
        float y = buttonY(bit);
        float width = (bit == BUTTON_SELECT || bit == BUTTON_START)
                ? Math.min(dp(27), getWidth() * 0.035f)
                : Math.min(dp(21), getWidth() * 0.028f);
        float height = dp(14);
        fill.setColor((buttons & bit) != 0 ? 0xC94B8CC8 : 0x71303C50);
        canvas.drawRoundRect(x - width, y - height, x + width, y + height,
                dp(10), dp(10), fill);
        stroke.setColor(0xE7EAF0F7);
        canvas.drawRoundRect(x - width, y - height, x + width, y + height,
                dp(10), dp(10), stroke);
        drawLabel(canvas, text, x, y + dp(4), dp(10));
    }

    private void drawLabel(Canvas canvas, String text, float x, float y, float size) {
        label.setTextSize(size);
        canvas.drawText(text, x, y, label);
    }

    private int hitButton(float x, float y) {
        int[] directions = {BUTTON_DPAD_UP, BUTTON_DPAD_DOWN,
                BUTTON_DPAD_LEFT, BUTTON_DPAD_RIGHT};
        float dpadHitRadius = dpadRadius() + dp(1);
        for (int bit : directions) {
            float dx = x - dpadX(bit);
            float dy = y - dpadY(bit);
            if (dx * dx + dy * dy <= dpadHitRadius * dpadHitRadius) return bit;
        }

        int[] actions = {BUTTON_A, BUTTON_B, BUTTON_X, BUTTON_Y};
        float actionHitRadius = actionRadius() + dp(3);
        for (int bit : actions) {
            float dx = x - buttonX(bit);
            float dy = y - buttonY(bit);
            if (dx * dx + dy * dy <= actionHitRadius * actionHitRadius) return bit;
        }

        int[] topButtons = {BUTTON_L1, BUTTON_L2, BUTTON_R1, BUTTON_R2,
                BUTTON_SELECT, BUTTON_START};
        for (int bit : topButtons) {
            float halfWidth = (bit == BUTTON_SELECT || bit == BUTTON_START)
                    ? Math.min(dp(24), getWidth() * 0.032f)
                    : Math.min(dp(17), getWidth() * 0.026f);
            float halfHeight = Math.min(dp(14), getHeight() * 0.045f);
            if (Math.abs(x - buttonX(bit)) <= halfWidth
                    && Math.abs(y - buttonY(bit)) <= halfHeight) return bit;
        }
        return 0;
    }

    private void updateStick(float x, float y, boolean rightStick) {
        float centerX = rightStick ? rightStickCenterX() : leftStickCenterX();
        float dx = (x - centerX) / Math.max(1f, stickRadius() * 0.90f) * sensitivity;
        float dy = (y - stickCenterY()) / Math.max(1f, stickRadius() * 0.90f) * sensitivity;
        float distance = (float) Math.hypot(dx, dy);
        final float deadZone = 0.10f;
        if (distance <= deadZone) {
            dx = 0f;
            dy = 0f;
        } else {
            float normalized = Math.min(1f, (distance - deadZone) / (1f - deadZone));
            dx = (dx / distance) * normalized;
            dy = (dy / distance) * normalized;
        }
        if (rightStick) {
            rightAxisX = Math.max(-1f, Math.min(1f, dx));
            rightAxisY = Math.max(-1f, Math.min(1f, dy));
        } else {
            leftAxisX = Math.max(-1f, Math.min(1f, dx));
            leftAxisY = Math.max(-1f, Math.min(1f, dy));
        }
    }

    private void rebuildButtonMask() {
        int next = 0;
        for (Integer bit : buttonPointers.values()) next |= bit;
        buttons = next;
    }

    private void emitState() {
        postInvalidateOnAnimation();
        if (listener != null) listener.onGamepadState(
                buttons, leftAxisX, leftAxisY, rightAxisX, rightAxisY);
    }

    private void pressPointer(MotionEvent event, int index) {
        int pointerId = event.getPointerId(index);
        if (buttonPointers.containsKey(pointerId)
                || pointerId == leftStickPointer || pointerId == rightStickPointer) {
            return;
        }

        float x = event.getX(index);
        float y = event.getY(index);
        float dy = y - stickCenterY();

        // Claim analog zones before testing buttons. The old order let the
        // D-pad's generous hit circle steal touches from the left stick.
        float leftDx = x - leftStickCenterX();
        float stickHitRadius = stickRadius() + dp(8);
        if (leftStickPointer == -1
                && leftDx * leftDx + dy * dy <= stickHitRadius * stickHitRadius) {
            leftStickPointer = pointerId;
            updateStick(x, y, false);
            emitState();
            return;
        }

        float rightDx = x - rightStickCenterX();
        if (rightStickPointer == -1
                && rightDx * rightDx + dy * dy <= stickHitRadius * stickHitRadius) {
            rightStickPointer = pointerId;
            updateStick(x, y, true);
            emitState();
            return;
        }

        int bit = hitButton(x, y);
        if (bit != 0) {
            buttonPointers.put(pointerId, bit);
            rebuildButtonMask();
            emitState();
        }
    }

    private void releasePointer(MotionEvent event, int index) {
        int pointerId = event.getPointerId(index);
        if (buttonPointers.remove(pointerId) != null) {
            rebuildButtonMask();
            emitState();
        }
        if (pointerId == leftStickPointer) {
            leftStickPointer = -1;
            leftAxisX = 0f;
            leftAxisY = 0f;
            emitState();
        }
        if (pointerId == rightStickPointer) {
            rightStickPointer = -1;
            rightAxisX = 0f;
            rightAxisY = 0f;
            emitState();
        }
    }

    @Override
    protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
        super.onSizeChanged(width, height, oldWidth, oldHeight);
        if (oldWidth == 0 && oldHeight == 0) return;
        releaseAllInputs();
    }

    private boolean handleLayoutDrag(MotionEvent event) {
        int action = event.getActionMasked(), index = event.getActionIndex();
        if (action == MotionEvent.ACTION_DOWN) {
            movePointer = event.getPointerId(index);
            moveLastX = event.getX(index);
            moveLastY = event.getY(index);
            return true;
        }
        if (action == MotionEvent.ACTION_MOVE && movePointer != -1) {
            int i = event.findPointerIndex(movePointer);
            if (i >= 0 && getWidth() > 0 && getHeight() > 0) {
                float x = event.getX(i), y = event.getY(i);
                float dx = x - moveLastX, dy = y - moveLastY;
                moveLastX = x;
                moveLastY = y;
                if (dx != 0f || dy != 0f) {
                    setPositionOffset(positionOffsetX + dx / getWidth(),
                            positionOffsetY + dy / getHeight());
                    if (layoutChangedListener != null) {
                        layoutChangedListener.onPositionChanged(positionOffsetX, positionOffsetY);
                    }
                }
            }
            return true;
        }
        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_POINTER_UP
                || action == MotionEvent.ACTION_CANCEL) movePointer = -1;
        return true;
    }

    private void cleanupMissingPointers(MotionEvent event) {
        boolean changed = false;
        java.util.Iterator<Map.Entry<Integer, Integer>> it = buttonPointers.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<Integer, Integer> entry = it.next();
            if (event.findPointerIndex(entry.getKey()) < 0) {
                it.remove();
                changed = true;
            }
        }
        if (leftStickPointer != -1 && event.findPointerIndex(leftStickPointer) < 0) {
            leftStickPointer = -1;
            leftAxisX = leftAxisY = 0f;
            changed = true;
        }
        if (rightStickPointer != -1 && event.findPointerIndex(rightStickPointer) < 0) {
            rightStickPointer = -1;
            rightAxisX = rightAxisY = 0f;
            changed = true;
        }
        if (changed) {
            rebuildButtonMask();
            emitState();
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (layoutEditMode) return handleLayoutDrag(event);
        int action = event.getActionMasked();
        int index = event.getActionIndex();

        if (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_POINTER_DOWN) {
            pressPointer(event, index);
            return true;
        }

        if (action == MotionEvent.ACTION_MOVE) {
            cleanupMissingPointers(event);
            if (leftStickPointer != -1) {
                int i = event.findPointerIndex(leftStickPointer);
                if (i >= 0) {
                    updateStick(event.getX(i), event.getY(i), false);
                    emitState();
                }
            }
            if (rightStickPointer != -1) {
                int i = event.findPointerIndex(rightStickPointer);
                if (i >= 0) {
                    updateStick(event.getX(i), event.getY(i), true);
                    emitState();
                }
            }
            return true;
        }

        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_POINTER_UP) {
            releasePointer(event, index);
            if (action == MotionEvent.ACTION_UP) releaseAllInputs();
            return true;
        }

        if (action == MotionEvent.ACTION_CANCEL) {
            buttonPointers.clear();
            buttons = 0;
            leftStickPointer = -1;
            rightStickPointer = -1;
            leftAxisX = leftAxisY = rightAxisX = rightAxisY = 0f;
            emitState();
            return true;
        }
        return true;
    }

    @Override
    protected void onDetachedFromWindow() {
        releaseAllInputs();
        super.onDetachedFromWindow();
    }
}