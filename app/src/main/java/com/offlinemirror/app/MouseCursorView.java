package com.offlinemirror.app;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

/** Visible local preview of the relative Bluetooth mouse pointer. */
public final class MouseCursorView extends View {
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint outline = new Paint(Paint.ANTI_ALIAS_FLAG);
    private float cursorX;
    private float cursorY;
    private boolean positioned;

    public MouseCursorView(Context context) {
        super(context);
        init();
    }

    public MouseCursorView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    private void init() {
        setLayerType(View.LAYER_TYPE_HARDWARE, null);
        setClickable(false);
        setFocusable(false);
        setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        fill.setColor(0xFFF9FBFF);
        fill.setStyle(Paint.Style.FILL);
        outline.setColor(0xE6000000);
        outline.setStyle(Paint.Style.STROKE);
        outline.setStrokeWidth(dp(2));
        outline.setStrokeJoin(Paint.Join.ROUND);
    }

    private float dp(float value) {
        return value * getResources().getDisplayMetrics().density;
    }

    public void resetCursor() {
        cursorX = getWidth() * 0.5f;
        cursorY = getHeight() * 0.5f;
        positioned = getWidth() > 0 && getHeight() > 0;
        invalidate();
    }

    public void moveBy(int dx, int dy) {
        if (getWidth() <= 0 || getHeight() <= 0) return;
        if (!positioned) resetCursor();
        float margin = dp(3);
        cursorX = Math.max(margin, Math.min(getWidth() - margin, cursorX + dx));
        cursorY = Math.max(margin, Math.min(getHeight() - margin, cursorY + dy));
        invalidate();
    }

    @Override
    protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
        super.onSizeChanged(width, height, oldWidth, oldHeight);
        if (!positioned && width > 0 && height > 0) resetCursor();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (!positioned) return;
        float size = dp(22);
        Path pointer = new Path();
        pointer.moveTo(cursorX, cursorY);
        pointer.lineTo(cursorX, cursorY + size);
        pointer.lineTo(cursorX + size * 0.27f, cursorY + size * 0.73f);
        pointer.lineTo(cursorX + size * 0.48f, cursorY + size * 1.12f);
        pointer.lineTo(cursorX + size * 0.64f, cursorY + size * 1.03f);
        pointer.lineTo(cursorX + size * 0.43f, cursorY + size * 0.66f);
        pointer.lineTo(cursorX + size * 0.88f, cursorY + size * 0.66f);
        pointer.close();
        canvas.drawPath(pointer, outline);
        canvas.drawPath(pointer, fill);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        // Never intercept touches; the mouse pad and gamepad remain interactive.
        return false;
    }
}
