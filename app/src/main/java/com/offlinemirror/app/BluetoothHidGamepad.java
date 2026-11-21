package com.offlinemirror.app;

import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothHidDevice;
import android.bluetooth.BluetoothHidDeviceAppSdpSettings;
import android.bluetooth.BluetoothProfile;
import android.content.Context;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Process;
import android.util.Log;

import java.util.concurrent.Executor;

/** Standard Bluetooth HID virtual gamepad peripheral. */
public final class BluetoothHidGamepad {
    public interface StatusListener {
        void onStatus(String message);
    }

    private static final String TAG = "OfflineMirror-BluetoothHID";
    private static final int GAMEPAD_REPORT_ID = 1;
    private static final int TOUCH_REPORT_ID = 2;

// Separate Bluetooth HID profile: Gamepad.
// Standalone, conservative HID Gamepad descriptor. D-pad is encoded as four
// buttons instead of a Hat Switch to avoid host-specific null-state handling.
private static final byte[] GAMEPAD_REPORT_DESCRIPTOR = new byte[] {
        0x05, 0x01, 0x09, 0x05, (byte) 0xA1, 0x01,
        (byte) 0x85, 0x01,
        0x05, 0x09, 0x19, 0x01, 0x29, 0x10,
        0x15, 0x00, 0x25, 0x01, 0x75, 0x01,
        (byte) 0x95, 0x10, (byte) 0x81, 0x02,
        0x05, 0x01, 0x09, 0x30, 0x09, 0x31, 0x09, 0x32, 0x09, 0x35,
        0x15, 0x00, 0x26, (byte) 0xFF, 0x00, 0x75, 0x08,
        (byte) 0x95, 0x04, (byte) 0x81, 0x02,
        (byte) 0xC0,
};

// Separate Bluetooth profile: experimental single-contact Touchscreen.
// Separate Bluetooth HID profile: experimental single-contact Touchscreen.
private static final byte[] TOUCH_REPORT_DESCRIPTOR = new byte[] {
        // Experimental HID Digitizer Touch Screen: one absolute contact (Report ID 2).
        0x05, 0x0D, 0x09, 0x04, (byte) 0xA1, 0x01,
        (byte) 0x85, 0x02,
        0x09, 0x22, (byte) 0xA1, 0x02,
        0x09, 0x42, 0x09, 0x32, 0x09, 0x47,
        0x15, 0x00, 0x25, 0x01,
        0x75, 0x01, (byte) 0x95, 0x03, (byte) 0x81, 0x02,
        0x75, 0x05, (byte) 0x95, 0x01, (byte) 0x81, 0x03,
        0x09, 0x51, 0x15, 0x00, 0x26, (byte) 0xFF, 0x00,
        0x75, 0x08, (byte) 0x95, 0x01, (byte) 0x81, 0x02,
        0x05, 0x01, 0x09, 0x30, 0x09, 0x31,
        0x16, 0x00, 0x00, 0x26, (byte) 0xFF, 0x7F,
        0x36, 0x00, 0x00, 0x46, (byte) 0xFF, 0x7F,
        0x55, 0x00, 0x65, 0x00,
        0x75, 0x10, (byte) 0x95, 0x02, (byte) 0x81, 0x02,
        (byte) 0xC0,
        0x05, 0x0D, 0x09, 0x54,
        0x15, 0x00, 0x25, 0x01,
        0x75, 0x08, (byte) 0x95, 0x01, (byte) 0x81, 0x02,
        // Contact Count Maximum feature report required by standard digitizers.
        0x09, 0x55, 0x15, 0x01, 0x25, 0x01,
        0x75, 0x08, (byte) 0x95, 0x01, (byte) 0xB1, 0x02,
        (byte) 0xC0
    };



    private final Context context;
    private final StatusListener listener;
    private final Object reportLock = new Object();

    private volatile BluetoothHidDevice hidDevice;
    private volatile BluetoothDevice target;
    private volatile boolean registered;
    private volatile boolean connected;

    private HandlerThread reportThread;
    private volatile Handler reportHandler;
    private byte[] pendingGamepadReport;
    private byte[] pendingTouchReport;
    private int lastQueuedButtons = -1;
    private boolean lastQueuedTouchPressed;
    private boolean touchReportQueued;
    private boolean gamepadReportQueued;
    private volatile boolean gamepadMode = false;
    private volatile boolean profileSwitchPending = false;
    private volatile boolean registrationRequested = false;
    private volatile boolean registeredProfileGamepad = false;

    private final Runnable sendLatestGamepadReport =
            () -> sendPendingGamepadReport();
    private final Runnable sendLatestTouchReport =
            () -> sendPendingTouchReport();

    public BluetoothHidGamepad(Context context, StatusListener listener) {
        this.context = context.getApplicationContext();
        this.listener = listener;
    }

    public void start(BluetoothDevice targetDevice) {
        if (Build.VERSION.SDK_INT < 28) {
            status("Bluetooth HID membutuhkan Android 9+");
            return;
        }
        target = targetDevice;
        try {
            android.bluetooth.BluetoothAdapter adapter =
                    android.bluetooth.BluetoothAdapter.getDefaultAdapter();
            if (adapter == null || !adapter.isEnabled()) {
                status("Bluetooth belum aktif");
                return;
            }
            ensureReportWorker();
            boolean requested = adapter.getProfileProxy(
                    context, profileListener, BluetoothProfile.HID_DEVICE);
            if (!requested) {
                status("Bluetooth HID Device tidak didukung firmware HP ini");
            } else {
                status("Mendaftarkan Bluetooth HID Touch + Gamepad + Home/Back...");
            }
        } catch (SecurityException denied) {
            status("Izin Bluetooth tidak diberikan");
        } catch (Exception error) {
            status("Bluetooth HID gagal dibuka: " + error.getMessage());
            Log.e(TAG, "Could not open HID profile", error);
        }
    }

    private void ensureReportWorker() {
        synchronized (reportLock) {
            if (reportThread != null && reportThread.isAlive()) return;
            reportThread = new HandlerThread(
                    "OfflineMirror-HID-Reports", Process.THREAD_PRIORITY_DISPLAY);
            reportThread.start();
            reportHandler = new Handler(reportThread.getLooper());
        }
    }

    private void registerCurrentProfile() {
        BluetoothHidDevice device = hidDevice;
        if (device == null) { status("Bluetooth HID belum siap"); return; }
        boolean pad = gamepadMode;
        registeredProfileGamepad = pad;
        registrationRequested = true;
        byte subclass = pad ? (byte) BluetoothHidDevice.SUBCLASS2_GAMEPAD : (byte) 0x00;
        byte[] descriptor = pad ? GAMEPAD_REPORT_DESCRIPTOR : TOUCH_REPORT_DESCRIPTOR;
        BluetoothHidDeviceAppSdpSettings sdp = new BluetoothHidDeviceAppSdpSettings(
                "OfflineMirror " + (pad ? "Gamepad" : "Touch"),
                "OfflineMirror Bluetooth " + (pad ? "Gamepad" : "Touch HID"),
                "OfflineMirror", subclass, descriptor);
        boolean ok = device.registerApp(sdp, null, null, context.getMainExecutor(), callback);
        if (!ok) registrationRequested = false;
        status(ok ? (pad ? "Jaringan 2 • profil Bluetooth Gamepad didaftarkan"
                         : "Jaringan 2 • profil Bluetooth Touch HID didaftarkan")
                  : "Pendaftaran profil Bluetooth gagal");
    }

    private final BluetoothProfile.ServiceListener profileListener =
            new BluetoothProfile.ServiceListener() {
        @Override
        public void onServiceConnected(int profile, BluetoothProfile proxy) {
            if (profile != BluetoothProfile.HID_DEVICE
                    || !(proxy instanceof BluetoothHidDevice)) {
                status("Profil Bluetooth HID tidak tersedia");
                return;
            }

            hidDevice = (BluetoothHidDevice) proxy;
            registerCurrentProfile();
        }

        @Override
        public void onServiceDisconnected(int profile) {
            connected = false;
            registered = false;
            registrationRequested = false;
            profileSwitchPending = false;
            hidDevice = null;
            resetQueuedState();
            status("Layanan Bluetooth HID terputus");
        }
    };

    private final BluetoothHidDevice.Callback callback =
            new BluetoothHidDevice.Callback() {
        @Override
        public void onAppStatusChanged(
                BluetoothDevice pluggedDevice, boolean isRegistered) {
            registered = isRegistered;
            if (!isRegistered) {
                registrationRequested = false;
                connected = false;
                resetQueuedState();
                if (profileSwitchPending) {
                    profileSwitchPending = false;
                    registerCurrentProfile();
                } else {
                    status("Profil Bluetooth HID tidak terdaftar");
                }
                return;
            }
            registrationRequested = false;
            if (target == null) {
                status("Jaringan 2 • pair OfflineMirror Controller dari HP Host");
            } else {
                status("Jaringan 2 • menyambungkan OfflineMirror Controller...");
                try {
                    if (hidDevice != null && !hidDevice.connect(target))
                        status("Koneksi ditolak • periksa pairing Bluetooth Host");
                } catch (SecurityException denied) {
                    status("Izin Bluetooth Connect diperlukan");
                }
            }
        }

        @Override
        public void onConnectionStateChanged(BluetoothDevice device, int state) {
            connected = state == BluetoothProfile.STATE_CONNECTED;
            if (connected) {
                target = device;
                resetQueuedState();
                status("Jaringan 2 aktif • Bluetooth " +
                        (gamepadMode ? "Gamepad" : "Touch HID") + " siap");
                sendNeutralReport();
            } else if (state == BluetoothProfile.STATE_CONNECTING) {
                status("Bluetooth HID sedang menyambungkan...");
            } else if (state == BluetoothProfile.STATE_DISCONNECTED) {
                resetQueuedState();
                status(profileSwitchPending ? "Jaringan 2 • mengganti profil Bluetooth..."
                        : "Jaringan 2 • Bluetooth terputus, sambungkan ulang dari Viewer");
            }
        }

        @Override
        public void onGetReport(
                BluetoothDevice device, byte type, byte id, int bufferSize) {
            if (hidDevice != null) {
                try {
                    if (type == BluetoothHidDevice.REPORT_TYPE_INPUT) {
                        if (id == GAMEPAD_REPORT_ID) {
                            hidDevice.replyReport(device, type, id, neutralReport());
                        } else if (id == TOUCH_REPORT_ID) {
                            hidDevice.replyReport(device, type, id, neutralTouchReport());
                        }
                    } else if (type == BluetoothHidDevice.REPORT_TYPE_FEATURE
                            && id == TOUCH_REPORT_ID) {
                        hidDevice.replyReport(device, type, id, new byte[] { 1 });
                    }
                } catch (SecurityException ignored) {
                    // The profile can disconnect while the host requests a report.
                } catch (Exception error) {
                    Log.w(TAG, "HID descriptor feature report failed", error);
                }
            }
        }
    };

    /** Android-standard button, hat, dual-stick and analog trigger report. */
    public void sendState(
            int buttons, float leftX, float leftY, float rightX, float rightY) {
        // The overlay visibility is controlled by ViewerActivity. Do not gate reports
        // on a second mode flag here: that flag can lag a UI transition and silently
        // discard every button event after switching from Touch HID to Gamepad.
        if (!connected || reportHandler == null || !gamepadMode) return;

        // Keep report construction out of the shared lock. Motion events arrive on
        // the UI thread and must never wait for Bluetooth Binder work.
        byte[] report = buildGamepadReport(buttons, leftX, leftY, rightX, rightY);
        synchronized (reportLock) {
            boolean buttonEdge = buttons != lastQueuedButtons;
            lastQueuedButtons = buttons;

            if (buttonEdge) {
                // Button edges are always queued immediately.
                pendingGamepadReport = null;
                gamepadReportQueued = false;
                reportHandler.removeCallbacks(sendLatestGamepadReport);
                reportHandler.post(
                        () -> sendReportNow(GAMEPAD_REPORT_ID, report));
            } else {
                // Analog movement is coalesced only while the worker is busy.
                pendingGamepadReport = report;
                if (!gamepadReportQueued) {
                    gamepadReportQueued = true;
                    reportHandler.post(sendLatestGamepadReport);
                }
            }
        }
    }

    /** Experimental single-contact absolute HID touchscreen input. */
    public void sendTouchContacts(int[] contactIds, float[] xs, float[] ys) {
        if (!connected || reportHandler == null || gamepadMode) return;

        boolean pressed = contactIds != null && contactIds.length > 0
                && xs != null && xs.length > 0 && ys != null && ys.length > 0;
        float x = pressed ? xs[0] : 0f;
        float y = pressed ? ys[0] : 0f;
        byte[] report = buildTouchReport(pressed, x, y);

        synchronized (reportLock) {
            boolean edge = pressed != lastQueuedTouchPressed;
            lastQueuedTouchPressed = pressed;
            if (edge) {
                // Preserve press/release edges: no debounce timer.
                pendingTouchReport = null;
                touchReportQueued = false;
                reportHandler.removeCallbacks(sendLatestTouchReport);
                reportHandler.post(() -> sendReportNow(TOUCH_REPORT_ID, report));
            } else {
                // Coalesce motion if busy, but enqueue immediately without a timer.
                pendingTouchReport = report;
                if (!touchReportQueued) {
                    touchReportQueued = true;
                    reportHandler.post(sendLatestTouchReport);
                }
            }
        }
    }

    public void sendTouchPoint(boolean pressed, float x, float y) {
        sendTouchContacts(pressed ? new int[]{1} : new int[0],
                pressed ? new float[]{x} : new float[0],
                pressed ? new float[]{y} : new float[0]);
    }

    private byte[] buildTouchReport(boolean pressed, float x, float y) {
        int px = toTouchCoordinate(x);
        int py = toTouchCoordinate(y);
        return new byte[] {
                (byte) (pressed ? 0x07 : 0x00),
                1,
                (byte) (px & 0xFF), (byte) ((px >>> 8) & 0xFF),
                (byte) (py & 0xFF), (byte) ((py >>> 8) & 0xFF),
                (byte) (pressed ? 1 : 0)
        };
    }

    private int toTouchCoordinate(float value) {
        if (Float.isNaN(value) || Float.isInfinite(value)) value = 0f;
        return Math.max(0, Math.min(32767,
                Math.round(Math.max(0f, Math.min(1f, value)) * 32767f)));
    }

    private byte[] neutralTouchReport() {
        return new byte[] { 0, 1, 0, 0, 0, 0, 0 };
    }

    /**
     * Wi-Fi (Network 1) carries mirroring only. Bluetooth (Network 2) registers
     * one standalone Touch HID or Gamepad profile at a time.
     */
    public void setInputMode(boolean useGamepad) {
        if (gamepadMode == useGamepad) return;
        BluetoothHidDevice device = hidDevice;
        BluetoothDevice receiver = target;
        boolean wasConnected = connected && device != null && receiver != null;
        if (wasConnected) {
            try {
                device.sendReport(receiver,
                        gamepadMode ? GAMEPAD_REPORT_ID : TOUCH_REPORT_ID,
                        gamepadMode ? neutralReport() : neutralTouchReport());
            } catch (Exception error) {
                Log.w(TAG, "Neutral report before input-mode switch failed", error);
            }
        }
        gamepadMode = useGamepad;
        resetQueuedState();
        if (device == null || !registered) {
            status("Mode disiapkan: " + (useGamepad ? "Gamepad" : "Touch HID")
                    + " • menunggu registrasi Bluetooth");
            return;
        }
        // Register a profile-specific descriptor so the Host cannot cache a
        // composite touchscreen/gamepad collection for a gamepad-only session.
        if (registered && device != null) {
            profileSwitchPending = true;
            try {
                device.unregisterApp();
                status("Mengganti descriptor Bluetooth ke " +
                        (useGamepad ? "Gamepad" : "Touch HID") + "...");
            } catch (Exception error) {
                profileSwitchPending = false;
                Log.e(TAG, "HID profile switch failed", error);
                status("Gagal mengganti profil HID: " + error.getMessage());
            }
        } else {
            status("Mode input disiapkan: " + (useGamepad ? "Gamepad" : "Touch HID")
                    + " • profil akan didaftarkan saat Bluetooth siap");
        }
    }

    public boolean isConnected() {
        return connected && registered && hidDevice != null;
    }

    public String getActiveProfileName() {
        return gamepadMode ? "Bluetooth Gamepad" : "Bluetooth Touch HID";
    }

    public boolean isGamepadMode() {
        return gamepadMode;
    }

    private void sendNeutralReport() {
        sendReportNow(gamepadMode ? GAMEPAD_REPORT_ID : TOUCH_REPORT_ID,
                gamepadMode ? neutralReport() : neutralTouchReport());
    }

    private void sendPendingTouchReport() {
        final byte[] report;
        synchronized (reportLock) {
            report = pendingTouchReport;
            pendingTouchReport = null;
            touchReportQueued = false;
        }
        // Never hold reportLock while calling the Bluetooth framework.
        sendReportNow(TOUCH_REPORT_ID, report);
    }

    private void sendPendingGamepadReport() {
        final byte[] report;
        synchronized (reportLock) {
            report = pendingGamepadReport;
            pendingGamepadReport = null;
            gamepadReportQueued = false;
        }
        // sendReport() crosses into the Android Bluetooth stack. Releasing the
        // shared lock first prevents UI MotionEvents from waiting on that call.
        sendReportNow(GAMEPAD_REPORT_ID, report);
    }

    private void sendReportNow(int reportId, byte[] report) {
        BluetoothHidDevice device = hidDevice;
        BluetoothDevice receiver = target;
        if (report == null || !connected || device == null || receiver == null) {
            return;
        }

        try {
            if (!device.sendReport(receiver, reportId, report)) {
                Log.w(TAG, "Bluetooth stack rejected HID report " + reportId);
                if (reportId == GAMEPAD_REPORT_ID || reportId == TOUCH_REPORT_ID) {
                    status("Jaringan 2 • HP Host menolak laporan " +
                            (gamepadMode ? "Gamepad" : "Touch HID"));
                }
            }
        } catch (SecurityException denied) {
            status("Izin Bluetooth Connect diperlukan");
        } catch (Exception error) {
            Log.w(TAG, "HID input report failed", error);
        }
    }

    private byte[] buildGamepadReport(
            int buttons, float lx, float ly, float rx, float ry) {
        byte[] report = new byte[6];
        int mask = buttons & 0xFFFF;
        report[0] = (byte) (mask & 0xFF);
        report[1] = (byte) ((mask >>> 8) & 0xFF);
        report[2] = toAxis(lx); // X
        report[3] = toAxis(ly); // Y
        report[4] = toAxis(rx); // Z
        report[5] = toAxis(ry); // Rz
        return report;
    }

    private byte[] neutralReport() {
        return new byte[] {
                0, 0,
                (byte) 128, (byte) 128, (byte) 128, (byte) 128
        };
    }

    // HID hat values: 0=up, clockwise in 45-degree increments, 8=neutral.
    private int hatValue(int buttons) {
        boolean up = (buttons & (1 << 10)) != 0;
        boolean down = (buttons & (1 << 11)) != 0;
        boolean left = (buttons & (1 << 12)) != 0;
        boolean right = (buttons & (1 << 13)) != 0;

        if (up && right) return 1;
        if (right && down) return 3;
        if (down && left) return 5;
        if (left && up) return 7;
        if (up) return 0;
        if (right) return 2;
        if (down) return 4;
        if (left) return 6;
        return 8;
    }

    private byte toAxis(float value) {
        if (Float.isNaN(value) || Float.isInfinite(value)) value = 0f;
        int encoded = Math.round(
                (Math.max(-1f, Math.min(1f, value)) + 1f) * 127.5f);
        return (byte) Math.max(0, Math.min(255, encoded));
    }

    private void resetQueuedState() {
        synchronized (reportLock) {
            lastQueuedButtons = -1;
            pendingGamepadReport = null;
            gamepadReportQueued = false;
            lastQueuedTouchPressed = false;
            pendingTouchReport = null;
            touchReportQueued = false;

            if (reportHandler != null) {
                reportHandler.removeCallbacksAndMessages(null);
                touchReportQueued = false;
                gamepadReportQueued = false;
            }
        }
    }

    public void stop() {
        try {
            if (connected && hidDevice != null && target != null) {
                hidDevice.sendReport(target,
                        gamepadMode ? GAMEPAD_REPORT_ID : TOUCH_REPORT_ID,
                        gamepadMode ? neutralReport() : neutralTouchReport());
            }
            if (hidDevice != null) {
                if (registered) hidDevice.unregisterApp();
                android.bluetooth.BluetoothAdapter adapter =
                        android.bluetooth.BluetoothAdapter.getDefaultAdapter();
                if (adapter != null) {
                    adapter.closeProfileProxy(
                            BluetoothProfile.HID_DEVICE, hidDevice);
                }
            }
        } catch (Exception error) {
            Log.w(TAG, "Error stopping HID controller", error);
        } finally {
            connected = false;
            registered = false;
            registrationRequested = false;
            profileSwitchPending = false;
            hidDevice = null;
            target = null;
            resetQueuedState();

            synchronized (reportLock) {
                if (reportThread != null) reportThread.quitSafely();
                reportHandler = null;
                reportThread = null;
            }
        }
    }

    private void status(String message) {
        if (listener != null) listener.onStatus(message);
    }
}
