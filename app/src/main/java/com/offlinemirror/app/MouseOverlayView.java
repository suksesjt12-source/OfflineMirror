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

/** Trackpad and click zones for a Bluetooth HID relative mouse. */
public final class MouseOverlayView extends View {
    public interface Listener {
        void onMouseState(int dx, int dy, int buttons, int wheel);
    }

    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Map<Integer, Integer> clickPointers = new HashMap<>();

    private Listener listener;
    private int mousePointer = -1;
    private float lastX;
    private float lastY;
    private float fractionalX;
    private float fractionalY;
    private int buttons;

    public MouseOverlayView(Context context) {
        super(context);
        init();
    }

    public MouseOverlayView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    private void init() {
        setLayerType(View.LAYER_TYPE_HARDWARE, null);
        fill.setStyle(Paint.Style.FILL);
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeWidth(dp(1.5f));
        text.setColor(0xFFF3F6FC);
        text.setTextAlign(Paint.Align.CENTER);
        text.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
        setClickable(true);
    }

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    public void releaseAllInputs() {
        clickPointers.clear();
        buttons = 0;
        mousePointer = -1;
        fractionalX = 0f;
        fractionalY = 0f;
        emit(0, 0, 0);
    }

    private float dp(float value) {
        return value * getResources().getDisplayMetrics().density;
    }

    private float padRight() {
        return getWidth() * 0.68f;
    }

    private void emit(int dx, int dy, int wheel) {
        if (listener != null) {
            listener.onMouseState(dx, dy, buttons, wheel);
        }
        postInvalidateOnAnimation();
    }

    private void rebuildButtons() {
        int next = 0;
        for (Integer value : clickPointers.values()) next |= value;
        buttons = next;
    }

    private void pressPointer(MotionEvent event, int index) {
        int pointerId = event.getPointerId(index);
        if (clickPointers.containsKey(pointerId) || pointerId == mousePointer) return;

        float x = event.getX(index);
        float y = event.getY(index);

        if (x >= padRight()) {
            int button = y < getHeight() * 0.5f ? 1 : 2;
            clickPointers.put(pointerId, button);
            rebuildButtons();
            emit(0, 0, 0);
        } else if (mousePointer == -1) {
            mousePointer = pointerId;
            lastX = x;
            lastY = y;
            fractionalX = 0f;
            fractionalY = 0f;
        }
    }

    private void releasePointer(MotionEvent event, int index) {
        int pointerId = event.getPointerId(index);
        if (clickPointers.remove(pointerId) != null) {
            rebuildButtons();
            emit(0, 0, 0);
        }

        if (pointerId == mousePointer) {
            mousePointer = -1;
            fractionalX = 0f;
            fractionalY = 0f;
        }
    }

    private void moveMousePointer(MotionEvent event) {
        if (mousePointer == -1) return;
        int pointerIndex = event.findPointerIndex(mousePointer);
        if (pointerIndex < 0) return;

        float x = event.getX(pointerIndex);
        float y = event.getY(pointerIndex);

        // Keep fractional movement so slow, precise trackpad motion is not lost.
        float rawX = (x - lastX) * 1.85f + fractionalX;
        float rawY = (y - lastY) * 1.85f + fractionalY;
        int dx = Math.round(rawX);
        int dy = Math.round(rawY);
        fractionalX = rawX - dx;
        fractionalY = rawY - dy;
        lastX = x;
        lastY = y;

        if (dx != 0 || dy != 0) emit(dx, dy, 0);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float width = getWidth();
        float height = getHeight();
        if (width <= 0f || height <= 0f) return;

        fill.setColor(0xCC172333);
        canvas.drawRoundRect(0, 0, width, height, dp(12), dp(12), fill);
        stroke.setColor(0xDCE2EAF5);
        canvas.drawRoundRect(
                dp(1), dp(1), width - dp(1), height - dp(1),
                dp(12), dp(12), stroke);

        float split = padRight();
        fill.setColor(0x8037475E);
        canvas.drawRoundRect(
                dp(5), dp(5), split - dp(4), height - dp(5),
                dp(9), dp(9), fill);

        text.setTextSize(dp(11));
        canvas.drawText(
                "MOUSE PAD", (split - dp(4)) / 2f, height / 2f + dp(4), text);

        float left = split + dp(2);
        float middle = height / 2f;
        fill.setColor((buttons & 1) != 0 ? 0xD9538DCB : 0xA6374A63);
        canvas.drawRoundRect(
                left, dp(5), width - dp(5), middle - dp(2),
                dp(7), dp(7), fill);
        fill.setColor((buttons & 2) != 0 ? 0xD9538DCB : 0xA6374A63);
        canvas.drawRoundRect(
                left, middle + dp(2), width - dp(5), height - dp(5),
                dp(7), dp(7), fill);

        text.setTextSize(dp(10));
        canvas.drawText(
                "LEFT", (left + width - dp(5)) / 2f, height * 0.27f, text);
        canvas.drawText(
                "RIGHT", (left + width - dp(5)) / 2f, height * 0.77f, text);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        int action = event.getActionMasked();
        int index = event.getActionIndex();

        if (action == MotionEvent.ACTION_DOWN
                || action == MotionEvent.ACTION_POINTER_DOWN) {
            pressPointer(event, index);
            return true;
        }

        if (action == MotionEvent.ACTION_MOVE) {
            moveMousePointer(event);
            return true;
        }

        if (action == MotionEvent.ACTION_UP
                || action == MotionEvent.ACTION_POINTER_UP) {
            releasePointer(event, index);
            return true;
        }

        if (action == MotionEvent.ACTION_CANCEL) {
            releaseAllInputs();
            return true;
        }

        if (action == MotionEvent.ACTION_SCROLL) {
            int wheel = -Math.round(
                    event.getAxisValue(MotionEvent.AXIS_VSCROLL));
            if (wheel != 0) emit(0, 0, wheel);
            return true;
        }

        return true;
    }
}
