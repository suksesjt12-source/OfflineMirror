package com.offlinemirror.app;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

import java.util.HashMap;
import java.util.Map;

/** Trackpad, double-tap click and four dedicated Bluetooth mouse actions. */
public final class MouseOverlayView extends View {
    public interface Listener {
        void onMouseState(int dx, int dy, int buttons, int wheel);
    }
    public interface LayoutMoveListener {
        void onMove(float dx, float dy);
    }

    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Map<Integer, Integer> clickPointers = new HashMap<>();
    private Listener listener;
    private LayoutMoveListener layoutMoveListener;
    private int mousePointer = -1, movePointer = -1;
    private float lastX, lastY, fractionalX, fractionalY;
    private float downX, downY, lastTapX, lastTapY;
    private long downAt, lastTapAt;
    private int buttons;
    private boolean layoutEditMode;

    public MouseOverlayView(Context context) { super(context); init(); }
    public MouseOverlayView(Context context, AttributeSet attrs) { super(context, attrs); init(); }

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

    public void setListener(Listener listener) { this.listener = listener; }
    public void setLayoutMoveListener(LayoutMoveListener listener) { layoutMoveListener = listener; }
    public void setLayoutEditMode(boolean enabled) {
        layoutEditMode = enabled;
        movePointer = -1;
        if (enabled) releaseAllInputs();
        invalidate();
    }

    public void releaseAllInputs() {
        clickPointers.clear();
        buttons = 0;
        mousePointer = -1;
        movePointer = -1;
        fractionalX = fractionalY = 0f;
        emit(0, 0, 0);
    }

    private float dp(float value) {
        return value * getResources().getDisplayMetrics().density;
    }
    private float padRight() { return getWidth() * 0.64f; }
    private void emit(int dx, int dy, int wheel) {
        if (listener != null) listener.onMouseState(dx, dy, buttons, wheel);
        postInvalidateOnAnimation();
    }
    private void clickPulse(int button) {
        buttons |= button;
        emit(0, 0, 0);
        buttons &= ~button;
        emit(0, 0, 0);
    }
    private void rebuildButtons() {
        int next = 0;
        for (Integer value : clickPointers.values()) next |= value;
        buttons = next;
    }

    private int actionAt(float y) {
        float row = getHeight() / 4f;
        if (y < row) return 3;       // scroll up
        if (y < row * 2f) return 1;  // left click
        if (y < row * 3f) return 2;  // right click
        return 4;                   // scroll down
    }

    private void pressPointer(MotionEvent event, int index) {
        int pointerId = event.getPointerId(index);
        if (clickPointers.containsKey(pointerId) || pointerId == mousePointer) return;
        float x = event.getX(index), y = event.getY(index);
        if (x >= padRight()) {
            int action = actionAt(y);
            if (action == 3) emit(0, 0, 1);
            else if (action == 4) emit(0, 0, -1);
            else {
                clickPointers.put(pointerId, action);
                rebuildButtons();
                emit(0, 0, 0);
            }
        } else if (mousePointer == -1) {
            mousePointer = pointerId;
            downX = x;
            downY = y;
            downAt = SystemClock.elapsedRealtime();
            lastX = x;
            lastY = y;
            fractionalX = fractionalY = 0f;
        }
    }

    private void releasePointer(MotionEvent event, int index) {
        int pointerId = event.getPointerId(index);
        if (clickPointers.remove(pointerId) != null) {
            rebuildButtons();
            emit(0, 0, 0);
        }
        if (pointerId == mousePointer) {
            float x = event.getX(index), y = event.getY(index);
            long now = SystemClock.elapsedRealtime();
            boolean tap = Math.hypot(x - downX, y - downY) <= dp(18)
                    && now - downAt <= 280L;
            if (tap && lastTapAt > 0L && now - lastTapAt <= 330L
                    && Math.hypot(x - lastTapX, y - lastTapY) <= dp(34)) {
                clickPulse(1);
                lastTapAt = 0L;
            } else if (tap) {
                lastTapAt = now;
                lastTapX = x;
                lastTapY = y;
            } else lastTapAt = 0L;
            mousePointer = -1;
            fractionalX = fractionalY = 0f;
        }
    }

    private void moveMousePointer(MotionEvent event) {
        if (mousePointer == -1) return;
        int pointerIndex = event.findPointerIndex(mousePointer);
        if (pointerIndex < 0) return;
        float x = event.getX(pointerIndex), y = event.getY(pointerIndex);
        float rawX = (x - lastX) * 1.85f + fractionalX;
        float rawY = (y - lastY) * 1.85f + fractionalY;
        int dx = Math.round(rawX), dy = Math.round(rawY);
        fractionalX = rawX - dx;
        fractionalY = rawY - dy;
        lastX = x;
        lastY = y;
        if (dx != 0 || dy != 0) emit(dx, dy, 0);
    }

    private boolean handleLayoutDrag(MotionEvent event) {
        int action = event.getActionMasked(), index = event.getActionIndex();
        if (action == MotionEvent.ACTION_DOWN) {
            movePointer = event.getPointerId(index);
            lastX = event.getX(index);
            lastY = event.getY(index);
            return true;
        }
        if (action == MotionEvent.ACTION_MOVE && movePointer != -1) {
            int i = event.findPointerIndex(movePointer);
            if (i >= 0) {
                float x = event.getX(i), y = event.getY(i);
                float dx = x - lastX, dy = y - lastY;
                lastX = x;
                lastY = y;
                if (layoutMoveListener != null && (dx != 0f || dy != 0f)) {
                    layoutMoveListener.onMove(dx, dy);
                }
            }
            return true;
        }
        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_POINTER_UP
                || action == MotionEvent.ACTION_CANCEL) movePointer = -1;
        return true;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float width = getWidth(), height = getHeight();
        if (width <= 0f || height <= 0f) return;
        fill.setColor(0xE9162232);
        canvas.drawRoundRect(0, 0, width, height, dp(14), dp(14), fill);
        stroke.setColor(layoutEditMode ? 0xFF75B9FF : 0xDCE2EAF5);
        stroke.setStrokeWidth(layoutEditMode ? dp(2.5f) : dp(1.5f));
        canvas.drawRoundRect(dp(1), dp(1), width - dp(1), height - dp(1),
                dp(14), dp(14), stroke);
        float split = padRight();
        fill.setColor(0xA52C3B50);
        canvas.drawRoundRect(dp(5), dp(5), split - dp(4), height - dp(5),
                dp(10), dp(10), fill);
        text.setTextSize(dp(11));
        canvas.drawText("TRACKPAD", (split - dp(4)) / 2f, height * 0.48f, text);
        text.setTextSize(dp(8));
        canvas.drawText("DOUBLE TAP = CLICK", (split - dp(4)) / 2f, height * 0.64f, text);
        float left = split + dp(2), right = width - dp(5), rowHeight = height / 4f;
        String[] labels = {"SCROLL ▲", "LEFT CLICK", "RIGHT CLICK", "SCROLL ▼"};
        int[] bits = {0, 1, 2, 0};
        for (int i = 0; i < 4; i++) {
            float top = i * rowHeight + dp(2), bottom = (i + 1) * rowHeight - dp(2);
            boolean pressed = bits[i] != 0 && (buttons & bits[i]) != 0;
            fill.setColor(pressed ? 0xE9538DCB : 0xA6374A63);
            canvas.drawRoundRect(left, top, right, bottom, dp(7), dp(7), fill);
            text.setTextSize(dp(8));
            canvas.drawText(labels[i], (left + right) / 2f,
                    (top + bottom) / 2f + dp(3), text);
        }
        if (layoutEditMode) {
            text.setColor(0xFF8CC8FF);
            text.setTextSize(dp(9));
            canvas.drawText("DRAG TO MOVE", width / 2f, height - dp(3), text);
            text.setColor(0xFFF3F6FC);
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (layoutEditMode) return handleLayoutDrag(event);
        int action = event.getActionMasked(), index = event.getActionIndex();
        if (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_POINTER_DOWN) {
            pressPointer(event, index);
            return true;
        }
        if (action == MotionEvent.ACTION_MOVE) {
            moveMousePointer(event);
            return true;
        }
        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_POINTER_UP) {
            releasePointer(event, index);
            return true;
        }
        if (action == MotionEvent.ACTION_CANCEL) {
            releaseAllInputs();
            return true;
        }
        if (action == MotionEvent.ACTION_SCROLL) {
            int wheel = -Math.round(event.getAxisValue(MotionEvent.AXIS_VSCROLL));
            if (wheel != 0) emit(0, 0, wheel);
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
