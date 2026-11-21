package com.offlinemirror.app;

import android.accessibilityservice.AccessibilityService;
import android.os.Handler;
import android.os.Looper;
import android.view.accessibility.AccessibilityEvent;

/** Host-side bridge for Android global navigation; it does not inspect screen content. */
public final class RemoteNavigationAccessibilityService extends AccessibilityService {
    private static volatile RemoteNavigationAccessibilityService instance;

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        // No window text, screen content, or gestures are inspected.
    }

    @Override
    public void onInterrupt() {
    }

    @Override
    public void onDestroy() {
        if (instance == this) instance = null;
        super.onDestroy();
    }

    public static boolean requestGlobalNavigation(int action) {
        final RemoteNavigationAccessibilityService service = instance;
        if (service == null || (action != 1 && action != 2)) return false;
        new Handler(Looper.getMainLooper()).post(() -> {
            int globalAction = action == 1 ? GLOBAL_ACTION_HOME : GLOBAL_ACTION_BACK;
            try {
                service.performGlobalAction(globalAction);
            } catch (Exception error) {
                android.util.Log.w("OfflineMirror", "Global navigation action failed", error);
            }
        });
        return true;
    }
}
