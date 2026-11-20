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

/** Transparent touch controller for Bluetooth HID and remote touch gestures. */
public final class GamepadOverlayView extends View {
    public interface Listener {
        void onGamepadState(int buttons, float leftX, float leftY);
        void onDirectGesture(int type, float x1, float y1, float x2, float y2, int durationMs);
    }

    // First ten bits map to standard HID buttons. D-pad bits are encoded as a HID hat switch.
    private static final int BUTTON_A = 1 << 0;
    private static final int BUTTON_B = 1 << 1;
    private static final int BUTTON_X = 1 << 2;
    private static final int BUTTON_Y = 1 << 3;
    private static final int BUTTON_L1 = 1 << 4;
    private static final int BUTTON_R1 = 1 << 5;
    private static final int BUTTON_SELECT = 1 << 6;
    private static final int BUTTON_START = 1 << 7;
    private static final int BUTTON_L2 = 1 << 8;
    private static final int BUTTON_R2 = 1 << 9;
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

    public void setSensitivity(float value) {
        sensitivity = Math.max(0.5f, Math.min(1.8f, value));
    }

    /** 0 = Classic, 1 = Compact, 2 = Left-handed. */
    public void setLayoutPreset(int preset) {
        layoutPreset = Math.max(0, Math.min(2, preset));
        invalidate();
    }

    private float sizeScale() { return layoutPreset == 1 ? 0.78f : 1.0f; }

    // The visible video is center-cropped to fill the viewer. These values map
    // a visible touch back to the original stream, including a 90-degree rotation.
    private float videoLeft;
    private float videoTop;
    private float videoWidth = -1f;
    private float videoHeight = -1f;
    private int videoRotation;

    private int buttons;
    private int joystickPointer = -1;
    private float axisX;
    private float axisY;
    private float directDownX;
    private float directDownY;
    private float directLastX;
    private float directLastY;
    private long directDownTime;
    private int directPointer = -1;

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
        joystickPointer = -1;
        directPointer = -1;
        axisX = axisY = 0f;
        emitState();
    }

    public void setVideoTransform(float left, float top, float width, float height,
                                  int rotationDegrees) {
        videoLeft = left;
        videoTop = top;
        videoWidth = Math.max(1f, width);
        videoHeight = Math.max(1f, height);
        videoRotation = rotationDegrees;
    }

    private boolean isInsideVideoContent(float x, float y) {
        // The cropped image covers the complete viewer viewport.
        return x >= 0f && y >= 0f && x <= getWidth() && y <= getHeight();
    }

    private float normalizeVideoX(float x, float y) {
        float u = (x - videoLeft) / Math.max(1f, videoWidth);
        float v = (y - videoTop) / Math.max(1f, videoHeight);
        if (videoRotation == 90) return clamp(v);
        if (videoRotation == -90) return clamp(1f - v);
        return clamp(u);
    }

    private float normalizeVideoY(float x, float y) {
        float u = (x - videoLeft) / Math.max(1f, videoWidth);
        float v = (y - videoTop) / Math.max(1f, videoHeight);
        if (videoRotation == 90) return clamp(1f - u);
        if (videoRotation == -90) return clamp(u);
        return clamp(v);
    }

    private float dp(float value) {
        return value * getResources().getDisplayMetrics().density;
    }

    private boolean isLandscapeLayout() {
        return getWidth() > getHeight();
    }

    private float stickCenterX() {
        if (layoutPreset == 2) return getWidth() * 0.82f;
        return getWidth() * (isLandscapeLayout() ? 0.14f : 0.20f);
    }

    private float stickCenterY() {
        return getHeight() * 0.81f;
    }

    private float dpadCenterX() {
        return getWidth() * (layoutPreset == 2 ? 0.16f : 0.14f);
    }

    private float dpadCenterY() {
        return getHeight() * (isLandscapeLayout() ? 0.52f : 0.48f);
    }

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
        if (bit == BUTTON_A || bit == BUTTON_Y) x = getWidth() * 0.88f;
        else if (bit == BUTTON_B) x = getWidth() * 0.94f;
        else if (bit == BUTTON_X) x = getWidth() * 0.82f;
        else if (bit == BUTTON_L1) x = getWidth() * 0.08f;
        else if (bit == BUTTON_L2) x = getWidth() * 0.20f;
        else if (bit == BUTTON_SELECT) x = getWidth() * 0.43f;
        else if (bit == BUTTON_START) x = getWidth() * 0.57f;
        else if (bit == BUTTON_R2) x = getWidth() * 0.80f;
        else if (bit == BUTTON_R1) x = getWidth() * 0.92f;
        else x = getWidth() * 0.88f;
        return layoutPreset == 2 ? getWidth() - x : x;
    }

    private float buttonY(int bit) {
        if (bit == BUTTON_Y) return getHeight() * 0.65f;
        if (bit == BUTTON_A) return getHeight() * 0.87f;
        if (bit == BUTTON_X || bit == BUTTON_B) return getHeight() * 0.76f;
        return getHeight() * (isLandscapeLayout() ? 0.10f : 0.08f);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (getWidth() == 0 || getHeight() == 0) return;

        float radius = stickRadius();
        float cx = stickCenterX();
        float cy = stickCenterY();
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
        drawLabel(canvas, "L", knobX, knobY + dp(5), dp(15));

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
        float width = (bit == BUTTON_SELECT || bit == BUTTON_START) ? dp(37) : dp(27);
        float height = dp(16);
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
        float dpadHitRadius = dpadRadius() + dp(7);
        for (int bit : directions) {
            float dx = x - dpadX(bit);
            float dy = y - dpadY(bit);
            if (dx * dx + dy * dy <= dpadHitRadius * dpadHitRadius) return bit;
        }

        int[] actions = {BUTTON_A, BUTTON_B, BUTTON_X, BUTTON_Y};
        float actionHitRadius = actionRadius() + dp(5);
        for (int bit : actions) {
            float dx = x - buttonX(bit);
            float dy = y - buttonY(bit);
            if (dx * dx + dy * dy <= actionHitRadius * actionHitRadius) return bit;
        }

        int[] topButtons = {BUTTON_L1, BUTTON_L2, BUTTON_R1, BUTTON_R2,
                BUTTON_SELECT, BUTTON_START};
        for (int bit : topButtons) {
            float width = (bit == BUTTON_SELECT || bit == BUTTON_START) ? dp(42) : dp(32);
            if (Math.abs(x - buttonX(bit)) <= width
                    && Math.abs(y - buttonY(bit)) <= dp(22)) return bit;
        }
        return 0;
    }

    private void updateStick(float x, float y) {
        float dx = (x - stickCenterX()) / Math.max(1f, stickRadius() * 0.78f) * sensitivity;
        float dy = (y - stickCenterY()) / Math.max(1f, stickRadius() * 0.78f) * sensitivity;
        float distance = (float) Math.hypot(dx, dy);
        if (distance > 1f) {
            dx /= distance;
            dy /= distance;
        }
        axisX = Math.max(-1f, Math.min(1f, dx));
        axisY = Math.max(-1f, Math.min(1f, dy));
    }

    private void rebuildButtonMask() {
        int next = 0;
        for (Integer bit : buttonPointers.values()) next |= bit;
        buttons = next;
    }

    private void emitState() {
        postInvalidateOnAnimation();
        if (listener != null) listener.onGamepadState(buttons, axisX, axisY);
    }

    private void pressPointer(MotionEvent event, int index) {
        int pointerId = event.getPointerId(index);
        float x = event.getX(index);
        float y = event.getY(index);

        int bit = hitButton(x, y);
        if (bit != 0) {
            buttonPointers.put(pointerId, bit);
            rebuildButtonMask();
            emitState();
            return;
        }

        float dx = x - stickCenterX();
        float dy = y - stickCenterY();
        if (joystickPointer == -1
                && dx * dx + dy * dy <= Math.pow(stickRadius() + dp(25), 2)) {
            joystickPointer = pointerId;
            updateStick(x, y);
            emitState();
            return;
        }

        if (directPointer == -1 && isInsideVideoContent(x, y)) {
            directPointer = pointerId;
            directDownX = directLastX = x;
            directDownY = directLastY = y;
            directDownTime = event.getEventTime();
        }
    }

    private void releasePointer(MotionEvent event, int index) {
        int pointerId = event.getPointerId(index);
        if (buttonPointers.remove(pointerId) != null) {
            rebuildButtonMask();
            emitState();
        }
        if (pointerId == joystickPointer) {
            joystickPointer = -1;
            axisX = 0f;
            axisY = 0f;
            emitState();
        }
        if (pointerId == directPointer) {
            directLastX = event.getX(index);
            directLastY = event.getY(index);
            sendDirectGesture(event.getEventTime());
            directPointer = -1;
        }
    }

    private void sendDirectGesture(long eventTime) {
        if (listener == null || getWidth() <= 0 || getHeight() <= 0) return;
        float x1 = normalizeVideoX(directDownX, directDownY);
        float y1 = normalizeVideoY(directDownX, directDownY);
        float x2 = normalizeVideoX(directLastX, directLastY);
        float y2 = normalizeVideoY(directLastX, directLastY);
        long duration = Math.max(1, eventTime - directDownTime);
        float distance = (float) Math.hypot(directLastX - directDownX,
                directLastY - directDownY);
        int type = distance >= dp(20) ? 3 : (duration >= 500 ? 2 : 1);
        listener.onDirectGesture(type, x1, y1, x2, y2,
                type == 1 ? 90 : (int) Math.min(1200, duration));
    }

    private float clamp(float value) {
        return Math.max(0f, Math.min(1f, value));
    }

    @Override
    protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
        super.onSizeChanged(width, height, oldWidth, oldHeight);
        if (oldWidth == 0 && oldHeight == 0) return;
        releaseAllInputs();
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        int action = event.getActionMasked();
        int index = event.getActionIndex();

        if (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_POINTER_DOWN) {
            pressPointer(event, index);
            return true;
        }

        if (action == MotionEvent.ACTION_MOVE) {
            if (joystickPointer != -1) {
                int i = event.findPointerIndex(joystickPointer);
                if (i >= 0) {
                    updateStick(event.getX(i), event.getY(i));
                    emitState();
                }
            }
            if (directPointer != -1) {
                int i = event.findPointerIndex(directPointer);
                if (i >= 0) {
                    directLastX = event.getX(i);
                    directLastY = event.getY(i);
                }
            }
            return true;
        }

        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_POINTER_UP) {
            releasePointer(event, index);
            return true;
        }

        if (action == MotionEvent.ACTION_CANCEL) {
            buttonPointers.clear();
            buttons = 0;
            joystickPointer = -1;
            directPointer = -1;
            axisX = 0f;
            axisY = 0f;
            emitState();
            return true;
        }
        return true;
    }
}