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
    private static final int NAVIGATION_REPORT_ID = 3;

    /*
     * Report 1: Android Game Pad collection. Button usages include L3/R3;
     * D-pad is a HID hat, X/Y and Z/Rz are analog sticks, C5/C4 are triggers.
     */
    private static final byte[] REPORT_DESCRIPTOR = new byte[] {
        0x05, 0x01, 0x09, 0x05, (byte) 0xA1, 0x01,
        (byte) 0x85, 0x01,

        0x05, 0x09,
        0x09, 0x01, 0x09, 0x02, 0x09, 0x04, 0x09, 0x05,
        0x09, 0x07, 0x09, 0x08, 0x09, 0x09, 0x09, 0x0A,
        0x09, 0x0B, 0x09, 0x0C, 0x09, 0x0E, 0x09, 0x0F,
        0x15, 0x00, 0x25, 0x01, 0x75, 0x01,
        (byte) 0x95, 0x0C, (byte) 0x81, 0x02,
        0x75, 0x04, (byte) 0x95, 0x01, (byte) 0x81, 0x03,

        0x05, 0x01, 0x09, 0x39,
        0x15, 0x00, 0x25, 0x07, 0x35, 0x00,
        0x46, 0x3B, 0x01, 0x65, 0x14, 0x75, 0x04,
        (byte) 0x95, 0x01, (byte) 0x81, 0x42,
        0x75, 0x04, (byte) 0x95, 0x01, (byte) 0x81, 0x03,

        0x09, 0x30, 0x09, 0x31, 0x09, 0x32, 0x09, 0x35,
        0x15, 0x00, 0x26, (byte) 0xFF, 0x00, 0x75, 0x08,
        (byte) 0x95, 0x04, (byte) 0x81, 0x02,

        0x05, 0x02, 0x09, (byte) 0xC5, 0x09, (byte) 0xC4,
        0x15, 0x00, 0x26, (byte) 0xFF, 0x00, 0x75, 0x08,
        (byte) 0x95, 0x02, (byte) 0x81, 0x02,

        (byte) 0xC0,

        // Separate Consumer Control application, still part of this one
        // composite Bluetooth HID device. This keeps nav usages out of the
        // Game Pad input report so Android gamepad parsers see a clean layout.
        0x05, 0x0C, 0x09, 0x01, (byte) 0xA1, 0x01,
        (byte) 0x85, 0x03,
        0x0A, 0x23, 0x02, // AC Home -> KEYCODE_HOME
        0x0A, 0x24, 0x02, // AC Back -> KEYCODE_BACK
        0x0A, (byte) 0x9F, 0x02, // AC Task Manager -> Recent Apps
        0x15, 0x00, 0x25, 0x01, 0x75, 0x01,
        (byte) 0x95, 0x03, (byte) 0x81, 0x02,
        0x75, 0x05, (byte) 0x95, 0x01, (byte) 0x81, 0x03,
        (byte) 0xC0,

        // Experimental HID Digitizer Touch Screen: one absolute contact.
        0x05, 0x0D, 0x09, 0x04, (byte) 0xA1, 0x01,
        (byte) 0x85, 0x02,
        0x09, 0x22, (byte) 0xA1, 0x02,
        0x09, 0x42, 0x09, 0x32,
        0x15, 0x00, 0x25, 0x01,
        0x75, 0x01, (byte) 0x95, 0x02, (byte) 0x81, 0x02,
        0x75, 0x06, (byte) 0x95, 0x01, (byte) 0x81, 0x03,
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
    private Handler reportHandler;
    private byte[] pendingGamepadReport;
    private byte[] pendingTouchReport;
    private int lastQueuedButtons = -1;
    private boolean lastQueuedTouchPressed;
    private volatile boolean gamepadMode = false;
    private byte[] latestGamepadReport = new byte[] {
            0, 0, 8, (byte) 128, (byte) 128, (byte) 128, (byte) 128, 0, 0
    };
    private int navigationGeneration;

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
            byte gamepadSubclass = (byte) BluetoothHidDevice.SUBCLASS2_GAMEPAD;
            BluetoothHidDeviceAppSdpSettings sdp =
                    new BluetoothHidDeviceAppSdpSettings(
                            "OfflineMirror Controller",
                            "Bluetooth HID Touchscreen, Gamepad and Navigation",
                            "OfflineMirror",
                            gamepadSubclass,
                            REPORT_DESCRIPTOR);

            Executor executor = context.getMainExecutor();
            boolean accepted = hidDevice.registerApp(
                    sdp, null, null, executor, callback);
            status(accepted
                    ? (target == null
                        ? "HID Controller siap dipair dari Bluetooth Host..."
                        : "Mendaftarkan Bluetooth Controller...")
                    : "Pendaftaran Bluetooth HID ditolak sistem");
        }

        @Override
        public void onServiceDisconnected(int profile) {
            connected = false;
            registered = false;
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
                connected = false;
                resetQueuedState();
                status("Bluetooth HID tidak terdaftar");
                return;
            }

            if (target == null) {
                status("HID siap • pair OfflineMirror Controller dari Host");
            } else {
                status("HID siap • menyambungkan Touchscreen + Gamepad...");
                try {
                    if (hidDevice != null && !hidDevice.connect(target)) {
                        status("Koneksi ditolak • periksa pairing Bluetooth Host");
                    }
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
                status("Bluetooth terhubung • Touch HID + Gamepad + Home/Back siap");
                sendNeutralReports();
            } else if (state == BluetoothProfile.STATE_CONNECTING) {
                status("Bluetooth HID sedang menyambungkan...");
            } else if (state == BluetoothProfile.STATE_DISCONNECTED) {
                resetQueuedState();
                status("Bluetooth HID terputus • sambungkan ulang dari Viewer");
            }
        }

        @Override
        public void onGetReport(
                BluetoothDevice device, byte type, byte id, int bufferSize) {
            if (hidDevice != null && type == BluetoothHidDevice.REPORT_TYPE_INPUT) {
                try {
                    if (id == GAMEPAD_REPORT_ID) {
                        hidDevice.replyReport(device, type, id, neutralReport());
                    } else if (id == TOUCH_REPORT_ID) {
                        hidDevice.replyReport(device, type, id, neutralTouchReport());
                    } else if (id == NAVIGATION_REPORT_ID) {
                        hidDevice.replyReport(device, type, id, neutralNavigationReport());
                    }
                } catch (SecurityException ignored) {
                    // The profile can disconnect while the host requests a report.
                }
            }
        }
    };

    /** Android-standard button, hat, dual-stick and analog trigger report. */
    public void sendState(
            int buttons, float leftX, float leftY, float rightX, float rightY) {
        if (!connected || reportHandler == null || !gamepadMode) return;

        synchronized (reportLock) {
            byte[] report = buildGamepadReport(
                    buttons, leftX, leftY, rightX, rightY);
            latestGamepadReport = report.clone();
            boolean buttonEdge = buttons != lastQueuedButtons;
            lastQueuedButtons = buttons;

            if (buttonEdge) {
                // A state transition includes the latest axes. Send it directly
                // on the report worker and discard any obsolete axis-only frame.
                pendingGamepadReport = null;
                reportHandler.removeCallbacks(sendLatestGamepadReport);
                reportHandler.post(
                        () -> sendReportNow(GAMEPAD_REPORT_ID, report));
            } else {
                pendingGamepadReport = report;
                reportHandler.removeCallbacks(sendLatestGamepadReport);
                reportHandler.postDelayed(sendLatestGamepadReport, 8L);
            }
        }
    }

    /** Send one absolute HID touchscreen contact (normalized coordinates 0..1). */
    public void sendTouchPoint(boolean pressed, float x, float y) {
        if (!connected || reportHandler == null || gamepadMode) return;

        byte[] report = buildTouchReport(pressed, x, y);
        synchronized (reportLock) {
            boolean edge = pressed != lastQueuedTouchPressed;
            lastQueuedTouchPressed = pressed;
            if (edge) {
                pendingTouchReport = null;
                reportHandler.removeCallbacks(sendLatestTouchReport);
                reportHandler.post(() -> sendReportNow(TOUCH_REPORT_ID, report));
            } else {
                pendingTouchReport = report;
                reportHandler.removeCallbacks(sendLatestTouchReport);
                reportHandler.postDelayed(sendLatestTouchReport, 8L);
            }
        }
    }

    private byte[] buildTouchReport(boolean pressed, float x, float y) {
        int px = toTouchCoordinate(x);
        int py = toTouchCoordinate(y);
        return new byte[] {
                (byte) (pressed ? 0x03 : 0x00), // Tip Switch + In Range
                1,                              // Contact Identifier
                (byte) (px & 0xFF), (byte) ((px >>> 8) & 0xFF),
                (byte) (py & 0xFF), (byte) ((py >>> 8) & 0xFF),
                (byte) (pressed ? 1 : 0)        // Contact Count
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
     * Switches between mutually exclusive Touch HID and Gamepad modes while
     * keeping the same composite HID registration and Bluetooth connection.
     */
    public void setInputMode(boolean useGamepad) {
        if (!connected || reportHandler == null) {
            gamepadMode = useGamepad;
            return;
        }
        synchronized (reportLock) {
            if (gamepadMode == useGamepad) return;
            gamepadMode = useGamepad;
            navigationGeneration++;
            pendingGamepadReport = null;
            pendingTouchReport = null;
            latestGamepadReport = neutralReport();
            lastQueuedButtons = -1;
            lastQueuedTouchPressed = false;
            reportHandler.removeCallbacks(sendLatestGamepadReport);
            reportHandler.removeCallbacks(sendLatestTouchReport);
            reportHandler.post(this::sendNeutralReports);
        }
    }

    /** Send Recent Apps (3), Home (1), or Back (2) over the same Bluetooth HID device. */
    public boolean sendNavigation(int action) {
        if (!connected || reportHandler == null
                || (action != 1 && action != 2 && action != 3)) {
            return false;
        }
        synchronized (reportLock) {
            final byte[] pressed = new byte[] {
                    (byte) (action == 1 ? 0x01 : (action == 2 ? 0x02 : 0x04))
            };
            final int generation = ++navigationGeneration;
            reportHandler.post(() ->
                    sendReportNow(NAVIGATION_REPORT_ID, pressed));
            reportHandler.postDelayed(() -> {
                synchronized (reportLock) {
                    if (generation != navigationGeneration) return;
                }
                sendReportNow(NAVIGATION_REPORT_ID, neutralNavigationReport());
            }, 90L);
        }
        return true;
    }

    private byte[] neutralNavigationReport() {
        return new byte[] {0};
    }

    private void sendNeutralReports() {
        sendReportNow(GAMEPAD_REPORT_ID, neutralReport());
        sendReportNow(TOUCH_REPORT_ID, neutralTouchReport());
        sendReportNow(NAVIGATION_REPORT_ID, neutralNavigationReport());
    }

    private void sendPendingTouchReport() {
        synchronized (reportLock) {
            byte[] report = pendingTouchReport;
            pendingTouchReport = null;
            sendReportNow(TOUCH_REPORT_ID, report);
        }
    }

    private void sendPendingGamepadReport() {
        synchronized (reportLock) {
            byte[] report = pendingGamepadReport;
            pendingGamepadReport = null;
            // Serialize send with sendState so an older axis-only report cannot
            // race a D-pad release and be delivered after the newer neutral state.
            sendReportNow(GAMEPAD_REPORT_ID, report);
        }
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
            }
        } catch (SecurityException denied) {
            status("Izin Bluetooth Connect diperlukan");
        } catch (Exception error) {
            Log.w(TAG, "HID input report failed", error);
        }
    }

    private byte[] buildGamepadReport(
            int buttons, float lx, float ly, float rx, float ry) {
        byte[] report = new byte[9];
        int mask = buttons & 0x03FF;
        if ((buttons & (1 << 14)) != 0) mask |= (1 << 10); // L3
        if ((buttons & (1 << 15)) != 0) mask |= (1 << 11); // R3

        report[0] = (byte) (mask & 0xFF);
        report[1] = (byte) ((mask >>> 8) & 0x0F);
        report[2] = (byte) hatValue(buttons);
        report[3] = toAxis(lx); // X
        report[4] = toAxis(ly); // Y
        report[5] = toAxis(rx); // Z
        report[6] = toAxis(ry); // Rz
        report[7] = (byte) ((buttons & (1 << 6)) != 0 ? 255 : 0); // Brake
        report[8] = (byte) ((buttons & (1 << 7)) != 0 ? 255 : 0); // Accelerator
        return report;
    }

    private byte[] neutralReport() {
        return new byte[] {
                0, 0, 8,
                (byte) 128, (byte) 128, (byte) 128, (byte) 128,
                0, 0
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
            lastQueuedTouchPressed = false;
            pendingTouchReport = null;
            navigationGeneration++;
            latestGamepadReport = neutralReport();

            if (reportHandler != null) {
                reportHandler.removeCallbacksAndMessages(null);
            }
        }
    }

    public void stop() {
        try {
            if (connected && hidDevice != null && target != null) {
                hidDevice.sendReport(
                        target, GAMEPAD_REPORT_ID, neutralReport());
                hidDevice.sendReport(target, TOUCH_REPORT_ID, neutralTouchReport());
                hidDevice.sendReport(target, NAVIGATION_REPORT_ID, neutralNavigationReport());
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
