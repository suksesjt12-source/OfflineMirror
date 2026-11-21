package com.offlinemirror.app;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;


/**
 * Native-feeling touchpad: one finger moves the Host pointer, double-tap clicks,
 * two-finger vertical movement scrolls, and the side rail has only up/down buttons.
 */
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

    private Listener listener;
    private LayoutMoveListener layoutMoveListener;

    private int mousePointer = -1;
    private int secondScrollPointer = -1;
    private int scrollButtonPointer = -1;
    private int movePointer = -1;
    private int pressedScrollDirection;
    private boolean twoFingerGesture;
    private boolean layoutEditMode;

    private float lastX;
    private float lastY;
    private float fractionalX;
    private float fractionalY;
    private float firstDownX;
    private float firstDownY;
    private float lastTapX;
    private float lastTapY;
    private float lastScrollCenterY;
    private float scrollRemainder;
    private long firstDownAt;
    private long lastTapAt;
    private int scrollUpSensitivity = 1;
    private int scrollDownSensitivity = 1;

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
        setContentDescription("Trackpad mouse. Geser satu jari untuk menggerakkan pointer, ketuk dua kali untuk klik, geser dua jari untuk scroll.");
    }

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    public void setLayoutMoveListener(LayoutMoveListener listener) {
        layoutMoveListener = listener;
    }

    public void setScrollSensitivity(int up, int down) {
        scrollUpSensitivity = clampSensitivity(up);
        scrollDownSensitivity = clampSensitivity(down);
        invalidate();
    }

    private int clampSensitivity(int value) {
        return Math.max(1, Math.min(10, value));
    }

    public void setLayoutEditMode(boolean enabled) {
        layoutEditMode = enabled;
        movePointer = -1;
        if (enabled) releaseAllInputs();
        invalidate();
    }

    public void releaseAllInputs() {
        mousePointer = -1;
        secondScrollPointer = -1;
        scrollButtonPointer = -1;
        movePointer = -1;
        pressedScrollDirection = 0;
        twoFingerGesture = false;
        fractionalX = fractionalY = scrollRemainder = 0f;
        emit(0, 0, 0);
    }

    private float dp(float value) {
        return value * getResources().getDisplayMetrics().density;
    }

    private float padRight() {
        return getWidth() * 0.78f;
    }

    private void emit(int dx, int dy, int wheel) {
        if (listener != null) listener.onMouseState(dx, dy, 0, wheel);
        postInvalidateOnAnimation();
    }

    private void clickPulse() {
        if (listener != null) {
            listener.onMouseState(0, 0, 1, 0);
            listener.onMouseState(0, 0, 0, 0);
        }
        performClick();
        invalidate();
    }

    private int scrollButtonDirection(float y) {
        return y < getHeight() * 0.5f ? 1 : -1;
    }

    private int scrollAmount(int direction) {
        return direction > 0 ? scrollUpSensitivity : scrollDownSensitivity;
    }

    private void pressPointer(MotionEvent event, int index) {
        int pointerId = event.getPointerId(index);
        float x = event.getX(index);
        float y = event.getY(index);

        if (x >= padRight()) {
            if (scrollButtonPointer != -1) return;
            scrollButtonPointer = pointerId;
            pressedScrollDirection = scrollButtonDirection(y);
            emit(0, 0, pressedScrollDirection * scrollAmount(pressedScrollDirection));
            return;
        }

        if (mousePointer == -1) {
            mousePointer = pointerId;
            firstDownX = x;
            firstDownY = y;
            firstDownAt = SystemClock.elapsedRealtime();
            lastX = x;
            lastY = y;
            fractionalX = fractionalY = 0f;
            return;
        }

        if (secondScrollPointer == -1 && pointerId != mousePointer) {
            secondScrollPointer = pointerId;
            twoFingerGesture = true;
            scrollRemainder = 0f;
            int primaryIndex = event.findPointerIndex(mousePointer);
            if (primaryIndex >= 0) {
                lastScrollCenterY = (event.getY(primaryIndex) + y) * 0.5f;
            } else {
                lastScrollCenterY = y;
            }
            lastTapAt = 0L;
        }
    }

    private void releasePointer(MotionEvent event, int index) {
        int pointerId = event.getPointerId(index);

        if (pointerId == scrollButtonPointer) {
            scrollButtonPointer = -1;
            pressedScrollDirection = 0;
            invalidate();
            return;
        }

        if (twoFingerGesture
                && (pointerId == mousePointer || pointerId == secondScrollPointer)) {
            mousePointer = -1;
            secondScrollPointer = -1;
            twoFingerGesture = false;
            scrollRemainder = 0f;
            fractionalX = fractionalY = 0f;
            return;
        }

        if (pointerId == mousePointer) {
            float x = event.getX(index);
            float y = event.getY(index);
            long now = SystemClock.elapsedRealtime();
            boolean tap = Math.hypot(x - firstDownX, y - firstDownY) <= dp(18)
                    && now - firstDownAt <= 280L;

            if (tap && lastTapAt > 0L && now - lastTapAt <= 330L
                    && Math.hypot(x - lastTapX, y - lastTapY) <= dp(34)) {
                clickPulse();
                lastTapAt = 0L;
            } else if (tap) {
                lastTapAt = now;
                lastTapX = x;
                lastTapY = y;
            } else {
                lastTapAt = 0L;
            }

            mousePointer = -1;
            fractionalX = fractionalY = 0f;
        }
    }

    private void moveSinglePointer(MotionEvent event) {
        if (mousePointer == -1 || twoFingerGesture) return;
        int pointerIndex = event.findPointerIndex(mousePointer);
        if (pointerIndex < 0) return;

        float x = event.getX(pointerIndex);
        float y = event.getY(pointerIndex);
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

    private void moveTwoFingerScroll(MotionEvent event) {
        if (!twoFingerGesture || mousePointer == -1 || secondScrollPointer == -1) return;
        int firstIndex = event.findPointerIndex(mousePointer);
        int secondIndex = event.findPointerIndex(secondScrollPointer);
        if (firstIndex < 0 || secondIndex < 0) return;

        float centerY = (event.getY(firstIndex) + event.getY(secondIndex)) * 0.5f;
        float deltaY = lastScrollCenterY - centerY;
        lastScrollCenterY = centerY;
        if (Math.abs(deltaY) < 0.1f) return;

        int sensitivity = deltaY > 0f ? scrollUpSensitivity : scrollDownSensitivity;
        scrollRemainder += deltaY / Math.max(dp(24), dp(42)) * sensitivity;
        int steps = (int) scrollRemainder;
        if (steps != 0) {
            scrollRemainder -= steps;
            emit(0, 0, steps);
        }
    }

    private boolean handleLayoutDrag(MotionEvent event) {
        int action = event.getActionMasked();
        int index = event.getActionIndex();

        if (action == MotionEvent.ACTION_DOWN) {
            movePointer = event.getPointerId(index);
            lastX = event.getX(index);
            lastY = event.getY(index);
            return true;
        }

        if (action == MotionEvent.ACTION_MOVE && movePointer != -1) {
            int i = event.findPointerIndex(movePointer);
            if (i >= 0) {
                float x = event.getX(i);
                float y = event.getY(i);
                float dx = x - lastX;
                float dy = y - lastY;
                lastX = x;
                lastY = y;
                if (layoutMoveListener != null && (dx != 0f || dy != 0f)) {
                    layoutMoveListener.onMove(dx, dy);
                }
            }
            return true;
        }

        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_POINTER_UP
                || action == MotionEvent.ACTION_CANCEL) {
            movePointer = -1;
        }
        return true;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float width = getWidth();
        float height = getHeight();
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
        canvas.drawText("TRACKPAD", split * 0.5f, height * 0.43f, text);
        text.setTextSize(dp(8));
        canvas.drawText("1 JARI: POINTER  •  2 JARI: SCROLL",
                split * 0.5f, height * 0.60f, text);
        canvas.drawText("DOUBLE TAP: KLIK",
                split * 0.5f, height * 0.75f, text);

        float left = split + dp(2);
        float right = width - dp(5);
        float middle = height * 0.5f;
        float topTop = dp(5);
        float bottomBottom = height - dp(5);

        fill.setColor(pressedScrollDirection == 1 ? 0xE9538DCB : 0xA6374A63);
        canvas.drawRoundRect(left, topTop, right, middle - dp(2),
                dp(8), dp(8), fill);
        fill.setColor(pressedScrollDirection == -1 ? 0xE9538DCB : 0xA6374A63);
        canvas.drawRoundRect(left, middle + dp(2), right, bottomBottom,
                dp(8), dp(8), fill);

        text.setTextSize(dp(9));
        canvas.drawText("SCROLL", (left + right) * 0.5f, height * 0.27f, text);
        canvas.drawText("ATAS", (left + right) * 0.5f, height * 0.40f, text);
        canvas.drawText("SCROLL", (left + right) * 0.5f, height * 0.67f, text);
        canvas.drawText("BAWAH", (left + right) * 0.5f, height * 0.80f, text);

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

        int action = event.getActionMasked();
        int index = event.getActionIndex();

        if (action == MotionEvent.ACTION_DOWN
                || action == MotionEvent.ACTION_POINTER_DOWN) {
            pressPointer(event, index);
            return true;
        }

        if (action == MotionEvent.ACTION_MOVE) {
            if (twoFingerGesture) moveTwoFingerScroll(event);
            else moveSinglePointer(event);
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
            float axis = event.getAxisValue(MotionEvent.AXIS_VSCROLL);
            if (axis > 0f) emit(0, 0, Math.max(1, Math.round(axis * scrollUpSensitivity)));
            else if (axis < 0f) emit(0, 0, -Math.max(1,
                    Math.round(-axis * scrollDownSensitivity)));
            return true;
        }

        return true;
    }

    @Override
    public boolean performClick() {
        super.performClick();
        return true;
    }

    @Override
    protected void onDetachedFromWindow() {
        releaseAllInputs();
        super.onDetachedFromWindow();
    }
}
