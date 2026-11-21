package com.offlinemirror.app;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.annotation.TargetApi;
import android.graphics.Path;
import android.graphics.Point;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.view.Display;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;

public final class RemoteTouchAccessibilityService extends AccessibilityService {
    private static volatile RemoteTouchAccessibilityService instance;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        // The service does not inspect or store screen content.
    }

    @Override
    public void onInterrupt() {
        // No screen-reading operation runs in this service.
    }

    @Override
    public void onDestroy() {
        if (instance == this) instance = null;
        super.onDestroy();
    }

    public static boolean dispatchRemoteGesture(float[][][] normalizedPaths, long[] durations) {
        if (Build.VERSION.SDK_INT < 24 || normalizedPaths == null || durations == null
                || normalizedPaths.length == 0 || normalizedPaths.length > 5
                || normalizedPaths.length != durations.length) return false;
        RemoteTouchAccessibilityService service = instance;
        if (service == null) return false;
        service.mainHandler.post(() -> service.dispatchGestureOnMain(normalizedPaths, durations));
        return true;
    }

    public static boolean performRemoteGlobalAction(int action) {
        if (action != 1 && action != 2) return false;
        RemoteTouchAccessibilityService service = instance;
        if (service == null) return false;
        service.mainHandler.post(() -> service.performGlobalAction(
                action == 1 ? GLOBAL_ACTION_HOME : GLOBAL_ACTION_BACK));
        return true;
    }

    @TargetApi(24)
    private void dispatchGestureOnMain(float[][][] normalizedPaths, long[] durations) {
        if (Build.VERSION.SDK_INT < 24) return;
        Point displaySize = getRealDisplaySize();
        if (displaySize.x <= 1 || displaySize.y <= 1) return;

        GestureDescription.Builder builder = new GestureDescription.Builder();
        for (int index = 0; index < normalizedPaths.length; index++) {
            float[][] points = normalizedPaths[index];
            long duration = Math.max(40L, Math.min(10000L, durations[index]));
            if (points == null || points.length < 2 || points.length > 48) continue;

            Path path = new Path();
            path.moveTo(clamp(points[0][0]) * (displaySize.x - 1),
                    clamp(points[0][1]) * (displaySize.y - 1));
            for (int point = 1; point < points.length; point++) {
                path.lineTo(clamp(points[point][0]) * (displaySize.x - 1),
                        clamp(points[point][1]) * (displaySize.y - 1));
            }
            builder.addStroke(new GestureDescription.StrokeDescription(path, 0L, duration));
        }

        GestureDescription gesture = builder.build();
        if (gesture.getStrokeCount() > 0) {
            dispatchGesture(gesture, new GestureResultCallback() {
                @Override
                public void onCompleted(GestureDescription description) {
                    android.util.Log.d("OfflineMirror", "Direct Touch completed");
                }

                @Override
                public void onCancelled(GestureDescription description) {
                    android.util.Log.w("OfflineMirror", "Direct Touch cancelled by Android");
                }
            }, mainHandler);
        }
    }

    private Point getRealDisplaySize() {
        Point size = new Point();
        try {
            WindowManager manager = (WindowManager) getSystemService(WINDOW_SERVICE);
            Display display = manager == null ? null : manager.getDefaultDisplay();
            if (display != null) display.getRealSize(size);
        } catch (Exception ignored) {
            size.set(getResources().getDisplayMetrics().widthPixels,
                    getResources().getDisplayMetrics().heightPixels);
        }
        if (size.x <= 1 || size.y <= 1) {
            size.set(getResources().getDisplayMetrics().widthPixels,
                    getResources().getDisplayMetrics().heightPixels);
        }
        return size;
    }

    private float clamp(float value) {
        if (Float.isNaN(value) || Float.isInfinite(value)) return 0f;
        return Math.max(0f, Math.min(1f, value));
    }
}
