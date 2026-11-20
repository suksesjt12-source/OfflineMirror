package com.offlinemirror.app;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.graphics.Path;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.view.Display;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;

public class RemoteAccessibilityService extends AccessibilityService {
    private static volatile RemoteAccessibilityService instance;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        // No screen text or window content is inspected.
    }

    @Override
    public void onInterrupt() {
    }

    @Override
    public void onDestroy() {
        if (instance == this) instance = null;
        super.onDestroy();
    }

    public static boolean isEnabled() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && instance != null;
    }

    public static void dispatchRemoteGesture(int type, float x1, float y1,
                                             float x2, float y2, int durationMs) {
        // The gesture injection API is only available starting with Android 7.0.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return;
        RemoteAccessibilityService service = instance;
        if (service == null) return;
        service.mainHandler.post(() ->
                Api24GestureDispatcher.dispatch(service, type, x1, y1, x2, y2, durationMs));
    }

    /**
     * Kept in a separate class so Android 5/6 do not need to load API-24 classes.
     */
    private static final class Api24GestureDispatcher {
        static void dispatch(RemoteAccessibilityService service, int type,
                             float x1, float y1, float x2, float y2, int durationMs) {
            DisplayMetrics metrics = new DisplayMetrics();
            WindowManager wm = (WindowManager) service.getSystemService(WINDOW_SERVICE);
            Display display = wm.getDefaultDisplay();
            display.getRealMetrics(metrics);

            float startX = clamp(x1) * metrics.widthPixels;
            float startY = clamp(y1) * metrics.heightPixels;
            float endX = clamp(x2) * metrics.widthPixels;
            float endY = clamp(y2) * metrics.heightPixels;

            Path path = new Path();
            path.moveTo(startX, startY);
            if (type == 3) {
                path.lineTo(endX, endY);
            } else {
                path.lineTo(startX + 1f, startY + 1f);
            }

            long duration = Math.max(60, Math.min(1200, durationMs));
            long gestureDuration = type == 2 ? Math.max(600, duration) : duration;
            GestureDescription gesture = new GestureDescription.Builder()
                    .addStroke(new GestureDescription.StrokeDescription(
                            path, 0, gestureDuration))
                    .build();
            service.dispatchGesture(gesture, null, null);
        }
    }

    private static float clamp(float value) {
        return Math.max(0f, Math.min(1f, value));
    }
}
