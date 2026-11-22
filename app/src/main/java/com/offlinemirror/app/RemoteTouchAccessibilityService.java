package com.offlinemirror.app;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.graphics.Path;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.view.accessibility.AccessibilityEvent;

import java.util.LinkedHashMap;
import java.util.Map;

/** Authenticated Wi-Fi touch receiver. Dispatches gestures only and never reads screen content. */
public final class RemoteTouchAccessibilityService extends AccessibilityService {
    private static volatile RemoteTouchAccessibilityService instance;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Map<Integer, ActiveContact> activeContacts = new LinkedHashMap<>();
    private int[] pendingIds;
    private float[] pendingXs;
    private float[] pendingYs;
    private boolean dispatchInFlight;

    private static final class ActiveContact {
        final float x, y;
        final GestureDescription.StrokeDescription stroke;
        ActiveContact(float x, float y, GestureDescription.StrokeDescription stroke) {
            this.x = x; this.y = y; this.stroke = stroke;
        }
    }

    public static boolean sendTouchState(int[] ids, float[] xs, float[] ys) {
        RemoteTouchAccessibilityService service = instance;
        if (service == null || Build.VERSION.SDK_INT < 26) return false;
        int count = Math.min(5, Math.min(ids == null ? 0 : ids.length,
                Math.min(xs == null ? 0 : xs.length, ys == null ? 0 : ys.length)));
        int[] safeIds = new int[count];
        float[] safeXs = new float[count];
        float[] safeYs = new float[count];
        if (count > 0) {
            System.arraycopy(ids, 0, safeIds, 0, count);
            System.arraycopy(xs, 0, safeXs, 0, count);
            System.arraycopy(ys, 0, safeYs, 0, count);
        }
        service.mainHandler.post(() -> service.acceptSnapshot(safeIds, safeXs, safeYs));
        return true;
    }

    @Override protected void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent event) {
        // This service deliberately does not inspect accessibility event content.
    }

    @Override public void onInterrupt() {
        pendingIds = new int[0];
        pendingXs = new float[0];
        pendingYs = new float[0];
        dispatchLatest();
    }

    @Override public void onDestroy() {
        if (instance == this) instance = null;
        activeContacts.clear();
        pendingIds = null;
        pendingXs = null;
        pendingYs = null;
        super.onDestroy();
    }

    private void acceptSnapshot(int[] ids, float[] xs, float[] ys) {
        pendingIds = ids;
        pendingXs = xs;
        pendingYs = ys;
        dispatchLatest();
    }

    private void dispatchLatest() {
        if (dispatchInFlight || pendingIds == null || Build.VERSION.SDK_INT < 26) return;
        int[] ids = pendingIds;
        float[] xs = pendingXs;
        float[] ys = pendingYs;
        pendingIds = null;
        pendingXs = null;
        pendingYs = null;

        DisplayMetrics metrics = getResources().getDisplayMetrics();
        float maxX = Math.max(1, metrics.widthPixels - 1);
        float maxY = Math.max(1, metrics.heightPixels - 1);
        Map<Integer, float[]> desired = new LinkedHashMap<>();
        for (int i = 0; i < ids.length; i++) {
            if (ids[i] < 0 || ids[i] > 255) continue;
            float nx = Math.max(0f, Math.min(1f, xs[i]));
            float ny = Math.max(0f, Math.min(1f, ys[i]));
            desired.put(ids[i], new float[]{nx * maxX, ny * maxY});
        }

        GestureDescription.Builder builder = new GestureDescription.Builder();
        Map<Integer, ActiveContact> next = new LinkedHashMap<>();
        int strokes = 0;
        for (Map.Entry<Integer, ActiveContact> entry : activeContacts.entrySet()) {
            int id = entry.getKey();
            ActiveContact old = entry.getValue();
            float[] point = desired.get(id);
            boolean keep = point != null;
            float endX = keep ? point[0] : old.x;
            float endY = keep ? point[1] : old.y;
            Path path = new Path();
            path.moveTo(old.x, old.y);
            path.lineTo(endX + 0.05f, endY + 0.05f);
            GestureDescription.StrokeDescription continued =
                    old.stroke.continueStroke(path, 0, 24L, keep);
            builder.addStroke(continued);
            strokes++;
            if (keep) next.put(id, new ActiveContact(endX + 0.05f, endY + 0.05f, continued));
        }
        for (Map.Entry<Integer, float[]> entry : desired.entrySet()) {
            int id = entry.getKey();
            if (activeContacts.containsKey(id)) continue;
            float x = entry.getValue()[0], y = entry.getValue()[1];
            Path path = new Path();
            path.moveTo(x, y);
            path.lineTo(x + 0.05f, y + 0.05f);
            GestureDescription.StrokeDescription started =
                    new GestureDescription.StrokeDescription(path, 0, 24L, true);
            builder.addStroke(started);
            next.put(id, new ActiveContact(x + 0.05f, y + 0.05f, started));
            strokes++;
        }
        if (strokes == 0) {
            activeContacts.clear();
            return;
        }

        dispatchInFlight = true;
        boolean accepted = dispatchGesture(builder.build(), new GestureResultCallback() {
            @Override public void onCompleted(GestureDescription gestureDescription) {
                activeContacts.clear();
                activeContacts.putAll(next);
                dispatchInFlight = false;
                dispatchLatest();
            }
            @Override public void onCancelled(GestureDescription gestureDescription) {
                activeContacts.clear();
                dispatchInFlight = false;
                dispatchLatest();
            }
        }, mainHandler);
        if (!accepted) {
            dispatchInFlight = false;
            activeContacts.clear();
            pendingIds = null;
            pendingXs = null;
            pendingYs = null;
        }
    }
}
