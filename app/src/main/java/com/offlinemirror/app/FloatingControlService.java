package com.offlinemirror.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.os.Build;
import android.os.IBinder;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Draggable, user-visible floating Home/Back panel over other apps. */
public final class FloatingControlService extends Service {
    public interface NavigationListener {
        void onNavigation(int action);
    }

    private static final String CHANNEL_ID = "offline_mirror_floating_controls";
    private static final int NOTIFICATION_ID = 83;
    private static volatile boolean running;
    private static volatile NavigationListener navigationListener;

    private WindowManager windowManager;
    private View floatingView;

    public static boolean isRunning() {
        return running;
    }

    public static void setNavigationListener(NavigationListener listener) {
        navigationListener = listener;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
        Notification notification = buildNotification();
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_ID, notification,
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (Build.VERSION.SDK_INT >= 23
                && !android.provider.Settings.canDrawOverlays(this)) {
            stopSelf();
            return START_NOT_STICKY;
        }
        if (floatingView == null) attachFloatingWindow();
        running = floatingView != null;
        return START_STICKY;
    }

    private void attachFloatingWindow() {
        if (windowManager == null) return;

        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.HORIZONTAL);
        panel.setGravity(Gravity.CENTER_VERTICAL);
        panel.setPadding(dp(6), dp(5), dp(6), dp(5));
        panel.setBackgroundColor(0xE91A2637);
        panel.setElevation(dp(8));

        TextView drag = makeButton("OM", 0xFF30445F);
        drag.setContentDescription("Geser floating window OfflineMirror");
        panel.addView(drag, new LinearLayout.LayoutParams(dp(42), dp(42)));

        TextView back = makeButton("BACK", 0xFF40556F);
        panel.addView(back, new LinearLayout.LayoutParams(dp(58), dp(42)));
        back.setOnClickListener(v -> sendNavigation(2));

        TextView home = makeButton("HOME", 0xFF246B8D);
        panel.addView(home, new LinearLayout.LayoutParams(dp(58), dp(42)));
        home.setOnClickListener(v -> sendNavigation(1));

        TextView close = makeButton("×", 0xFF8B3947);
        panel.addView(close, new LinearLayout.LayoutParams(dp(42), dp(42)));
        close.setOnClickListener(v -> stopSelf());

        final WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                Build.VERSION.SDK_INT >= 26
                        ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                        : WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.TOP | Gravity.START;
        params.x = dp(12);
        params.y = dp(150);

        drag.setOnTouchListener(new View.OnTouchListener() {
            private int startX;
            private int startY;
            private float downX;
            private float downY;

            @Override
            public boolean onTouch(View view, MotionEvent event) {
                switch (event.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        startX = params.x;
                        startY = params.y;
                        downX = event.getRawX();
                        downY = event.getRawY();
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        params.x = startX + Math.round(event.getRawX() - downX);
                        params.y = startY + Math.round(event.getRawY() - downY);
                        try { windowManager.updateViewLayout(panel, params); }
                        catch (Exception ignored) {}
                        return true;
                    case MotionEvent.ACTION_UP:
                        return true;
                    default:
                        return false;
                }
            }
        });

        floatingView = panel;
        try {
            windowManager.addView(floatingView, params);
        } catch (Exception error) {
            floatingView = null;
            android.util.Log.e("OfflineMirror", "Could not attach floating controls", error);
            stopSelf();
        }
    }

    private TextView makeButton(String value, int color) {
        TextView button = new TextView(this);
        button.setText(value);
        button.setTextColor(Color.WHITE);
        button.setTextSize(11);
        button.setGravity(Gravity.CENTER);
        button.setBackgroundColor(color);
        button.setClickable(true);
        return button;
    }

    private void sendNavigation(int action) {
        NavigationListener listener = navigationListener;
        if (listener != null) listener.onNavigation(action);
    }

    private int dp(float value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID, "OfflineMirror floating controls",
                    NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("Keeps the user-requested floating controls available");
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) manager.createNotificationChannel(channel);
        }
    }

    private Notification buildNotification() {
        Notification.Builder builder = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);
        return builder.setContentTitle("OfflineMirror floating controls")
                .setContentText("Panel Home dan Back aktif")
                .setSmallIcon(android.R.drawable.ic_menu_view)
                .setOngoing(true)
                .build();
    }

    @Override
    public void onDestroy() {
        running = false;
        if (floatingView != null && windowManager != null) {
            try { windowManager.removeView(floatingView); } catch (Exception ignored) {}
        }
        floatingView = null;
        if (Build.VERSION.SDK_INT >= 24) stopForeground(STOP_FOREGROUND_REMOVE);
        else stopForeground(true);
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
