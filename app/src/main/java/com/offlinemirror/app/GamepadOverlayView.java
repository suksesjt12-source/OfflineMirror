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

/**
 * Transparent gamepad surface drawn above the live mirrored screen.
 * It emits actual HID button/axis state and forwards touches outside the
 * controller regions to the existing Wi-Fi remote-touch channel.
 */
public final class GamepadOverlayView extends View {
    public interface Listener {
        void onGamepadState(int buttons, float leftX, float leftY);
        void onDirectGesture(int type, float x1, float y1, float x2, float y2, int durationMs);
    }

    private static final int BUTTON_A = 1 << 0;
    private static final int BUTTON_B = 1 << 1;
    private static final int BUTTON_X = 1 << 2;
    private static final int BUTTON_Y = 1 << 3;
    private static final int BUTTON_L1 = 1 << 4;
    private static final int BUTTON_R1 = 1 << 5;
    private static final int BUTTON_SELECT = 1 << 8;
    private static final int BUTTON_START = 1 << 9;

    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint label = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Map<Integer, Integer> buttonPointers = new HashMap<>();
    private Listener listener;
    private float videoLeft;
    private float videoTop;
    private float videoWidth = -1f;
    private float videoHeight = -1f;

    private int buttons;
    private int joystickPointer = -1;
    private float axisX;
    private float axisY;
    private float stickX;
    private float stickY;
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
        fill.setColor(0x71303C50);
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeWidth(dp(2));
        stroke.setColor(0xDCE2EAF5);
        label.setColor(0xFFF4F7FC);
        label.setTextAlign(Paint.Align.CENTER);
        label.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
        label.setTextSize(dp(13));
        setClickable(true);
    }

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    /**
     * Defines the actual video image area inside the full-screen overlay.
     * Direct touch coordinates are normalized against this rectangle, not
     * against the letterboxed black margins.
     */
    public void setVideoContentRect(float left, float top, float width, float height) {
        videoLeft = left;
        videoTop = top;
        videoWidth = Math.max(1f, width);
        videoHeight = Math.max(1f, height);
    }

    private boolean isInsideVideoContent(float x, float y) {
        float left = videoWidth > 0f ? videoLeft : 0f;
        float top = videoHeight > 0f ? videoTop : 0f;
        float width = videoWidth > 0f ? videoWidth : Math.max(1f, getWidth());
        float height = videoHeight > 0f ? videoHeight : Math.max(1f, getHeight());
        return x >= left && x <= left + width && y >= top && y <= top + height;
    }

    private float normalizeVideoX(float x) {
        float left = videoWidth > 0f ? videoLeft : 0f;
        float width = videoWidth > 0f ? videoWidth : Math.max(1f, getWidth());
        return clamp((x - left) / width);
    }

    private float normalizeVideoY(float y) {
        float top = videoHeight > 0f ? videoTop : 0f;
        float height = videoHeight > 0f ? videoHeight : Math.max(1f, getHeight());
        return clamp((y - top) / height);
    }

    private float dp(float value) {
        return value * getResources().getDisplayMetrics().density;
    }

    private boolean isLandscapeLayout() {
        return getWidth() > getHeight();
    }

    private float stickCenterX() {
        return getWidth() * (isLandscapeLayout() ? 0.13f : 0.18f);
    }

    private float stickCenterY() {
        return getHeight() * (isLandscapeLayout() ? 0.78f : 0.78f);
    }

    private float stickRadius() {
        float shortSide = Math.min(getWidth(), getHeight());
        return Math.min(dp(isLandscapeLayout() ? 44 : 47), shortSide * 0.14f);
    }

    private float buttonX(int bit) {
        if (bit == BUTTON_A || bit == BUTTON_Y) return getWidth() * 0.82f;
        if (bit == BUTTON_B) return getWidth() * 0.92f;
        if (bit == BUTTON_X) return getWidth() * 0.72f;
        if (bit == BUTTON_L1) return getWidth() * (isLandscapeLayout() ? 0.13f : 0.20f);
        if (bit == BUTTON_R1) return getWidth() * (isLandscapeLayout() ? 0.87f : 0.80f);
        if (bit == BUTTON_SELECT) return getWidth() * 0.42f;
        if (bit == BUTTON_START) return getWidth() * 0.58f;
        return getWidth() * 0.78f;
    }

    private float buttonY(int bit) {
        if (bit == BUTTON_A) return getHeight() * 0.78f;
        if (bit == BUTTON_B || bit == BUTTON_X) return getHeight() * 0.88f;
        if (bit == BUTTON_Y) return getHeight() * 0.68f;
        if (bit == BUTTON_L1 || bit == BUTTON_R1) {
            return getHeight() * (isLandscapeLayout() ? 0.15f : 0.12f);
        }
        if (bit == BUTTON_SELECT || bit == BUTTON_START) {
            return getHeight() * (isLandscapeLayout() ? 0.15f : 0.12f);
        }
        return getHeight() * 0.12f;
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
        canvas.drawCircle(cx + axisX * radius * 0.68f,
                cy + axisY * radius * 0.68f, radius * 0.40f, fill);
        canvas.drawCircle(cx + axisX * radius * 0.68f,
                cy + axisY * radius * 0.68f, radius * 0.40f, stroke);
        drawLabel(canvas, "L", cx, cy + dp(5), dp(15));

        drawAction(canvas, BUTTON_Y, "Y", dp(19));
        drawAction(canvas, BUTTON_X, "X", dp(19));
        drawAction(canvas, BUTTON_B, "B", dp(19));
        drawAction(canvas, BUTTON_A, "A", dp(19));

        drawPill(canvas, BUTTON_L1, "L1", buttonX(BUTTON_L1), buttonY(BUTTON_L1));
        drawPill(canvas, BUTTON_R1, "R1", buttonX(BUTTON_R1), buttonY(BUTTON_R1));
        drawPill(canvas, BUTTON_SELECT, "SELECT",
                buttonX(BUTTON_SELECT), buttonY(BUTTON_SELECT));
        drawPill(canvas, BUTTON_START, "START",
                buttonX(BUTTON_START), buttonY(BUTTON_START));
    }

    private void drawAction(Canvas canvas, int bit, String text, float radius) {
        float x = buttonX(bit);
        float y = buttonY(bit);
        boolean pressed = (buttons & bit) != 0;
        fill.setColor(pressed ? 0xC94B8CC8 : 0x71303C50);
        canvas.drawCircle(x, y, radius, fill);
        stroke.setColor(0xE7EAF0F7);
        canvas.drawCircle(x, y, radius, stroke);
        drawLabel(canvas, text, x, y + dp(4), dp(13));
    }

    private void drawPill(Canvas canvas, int bit, String text, float x, float y) {
        float width = text.length() > 3 ? dp(42) : dp(35);
        float height = dp(17);
        boolean pressed = (buttons & bit) != 0;
        fill.setColor(pressed ? 0xC94B8CC8 : 0x71303C50);
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
        int[] bits = {BUTTON_A, BUTTON_B, BUTTON_X, BUTTON_Y};
        float r = dp(25);
        for (int bit : bits) {
            float dx = x - buttonX(bit);
            float dy = y - buttonY(bit);
            if (dx * dx + dy * dy <= r * r) return bit;
        }
        int[] topBits = {BUTTON_L1, BUTTON_R1, BUTTON_SELECT, BUTTON_START};
        for (int bit : topBits) {
            float dx = x - buttonX(bit);
            float dy = y - buttonY(bit);
            float width = bit == BUTTON_SELECT || bit == BUTTON_START ? dp(48) : dp(42);
            if (Math.abs(dx) <= width && Math.abs(dy) <= dp(22)) return bit;
        }
        return 0;
    }

    private void updateStick(float x, float y) {
        float dx = (x - stickCenterX()) / Math.max(1f, stickRadius() * 0.75f);
        float dy = (y - stickCenterY()) / Math.max(1f, stickRadius() * 0.75f);
        float distance = (float) Math.hypot(dx, dy);
        if (distance > 1f) {
            dx /= distance;
            dy /= distance;
        }
        axisX = Math.max(-1f, Math.min(1f, dx));
        axisY = Math.max(-1f, Math.min(1f, dy));
    }

    private void emitState() {
        invalidate();
        if (listener != null) listener.onGamepadState(buttons, axisX, axisY);
    }

    private void pressPointer(MotionEvent event, int index) {
        int pointerId = event.getPointerId(index);
        float x = event.getX(index);
        float y = event.getY(index);

        int bit = hitButton(x, y);
        if (bit != 0) {
            buttonPointers.put(pointerId, bit);
            buttons |= bit;
            emitState();
            return;
        }

        float dx = x - stickCenterX();
        float dy = y - stickCenterY();
        if (joystickPointer == -1
                && dx * dx + dy * dy <= Math.pow(stickRadius() + dp(24), 2)) {
            joystickPointer = pointerId;
            stickX = x;
            stickY = y;
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
        Integer bit = buttonPointers.remove(pointerId);
        if (bit != null) {
            buttons &= ~bit;
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
        float x1 = normalizeVideoX(directDownX);
        float y1 = normalizeVideoY(directDownY);
        float x2 = normalizeVideoX(directLastX);
        float y2 = normalizeVideoY(directLastY);
        long duration = Math.max(1, eventTime - directDownTime);
        float distance = (float) Math.hypot(
                directLastX - directDownX, directLastY - directDownY);
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

        // Release any held virtual buttons/stick during a rotation so no input
        // remains stuck when the overlay changes from portrait to landscape.
        buttonPointers.clear();
        buttons = 0;
        joystickPointer = -1;
        directPointer = -1;
        axisX = 0f;
        axisY = 0f;
        emitState();
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
                    stickX = event.getX(i);
                    stickY = event.getY(i);
                    updateStick(stickX, stickY);
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
