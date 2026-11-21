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
    private static final int REPORT_ID = 1;

// Separate Bluetooth HID profile: Gamepad.
private static final byte[] GAMEPAD_REPORT_DESCRIPTOR = new byte[] {
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
    };

// Separate Bluetooth HID profile: five-contact Touchscreen.
private static final byte[] TOUCH_REPORT_DESCRIPTOR = new byte[] {
        0x05, 0x0D, 0x09, 0x04, (byte) 0xA1, 0x01,
        (byte) 0x85, 0x01,
        0x09, 0x22, (byte) 0xA1, 0x02,
        0x09, 0x42, 0x09, 0x32, 0x15, 0x00, 0x25, 0x01,
        0x75, 0x01, (byte) 0x95, 0x02, (byte) 0x81, 0x02,
        0x75, 0x06, (byte) 0x95, 0x01, (byte) 0x81, 0x03,
        0x09, 0x51, 0x15, 0x01, 0x25, 0x05,
        0x75, 0x08, (byte) 0x95, 0x01, (byte) 0x81, 0x02,
        0x05, 0x01, 0x09, 0x30, 0x09, 0x31,
        0x16, 0x00, 0x00, 0x26, (byte) 0xFF, 0x7F,
        0x36, 0x00, 0x00, 0x46, (byte) 0xFF, 0x7F,
        0x55, 0x00, 0x65, 0x00, 0x75, 0x10,
        (byte) 0x95, 0x02, (byte) 0x81, 0x02, (byte) 0xC0,
        0x09, 0x22, (byte) 0xA1, 0x02,
        0x09, 0x42, 0x09, 0x32, 0x15, 0x00, 0x25, 0x01,
        0x75, 0x01, (byte) 0x95, 0x02, (byte) 0x81, 0x02,
        0x75, 0x06, (byte) 0x95, 0x01, (byte) 0x81, 0x03,
        0x09, 0x51, 0x15, 0x01, 0x25, 0x05,
        0x75, 0x08, (byte) 0x95, 0x01, (byte) 0x81, 0x02,
        0x05, 0x01, 0x09, 0x30, 0x09, 0x31,
        0x16, 0x00, 0x00, 0x26, (byte) 0xFF, 0x7F,
        0x36, 0x00, 0x00, 0x46, (byte) 0xFF, 0x7F,
        0x55, 0x00, 0x65, 0x00, 0x75, 0x10,
        (byte) 0x95, 0x02, (byte) 0x81, 0x02, (byte) 0xC0,
        0x09, 0x22, (byte) 0xA1, 0x02,
        0x09, 0x42, 0x09, 0x32, 0x15, 0x00, 0x25, 0x01,
        0x75, 0x01, (byte) 0x95, 0x02, (byte) 0x81, 0x02,
        0x75, 0x06, (byte) 0x95, 0x01, (byte) 0x81, 0x03,
        0x09, 0x51, 0x15, 0x01, 0x25, 0x05,
        0x75, 0x08, (byte) 0x95, 0x01, (byte) 0x81, 0x02,
        0x05, 0x01, 0x09, 0x30, 0x09, 0x31,
        0x16, 0x00, 0x00, 0x26, (byte) 0xFF, 0x7F,
        0x36, 0x00, 0x00, 0x46, (byte) 0xFF, 0x7F,
        0x55, 0x00, 0x65, 0x00, 0x75, 0x10,
        (byte) 0x95, 0x02, (byte) 0x81, 0x02, (byte) 0xC0,
        0x09, 0x22, (byte) 0xA1, 0x02,
        0x09, 0x42, 0x09, 0x32, 0x15, 0x00, 0x25, 0x01,
        0x75, 0x01, (byte) 0x95, 0x02, (byte) 0x81, 0x02,
        0x75, 0x06, (byte) 0x95, 0x01, (byte) 0x81, 0x03,
        0x09, 0x51, 0x15, 0x01, 0x25, 0x05,
        0x75, 0x08, (byte) 0x95, 0x01, (byte) 0x81, 0x02,
        0x05, 0x01, 0x09, 0x30, 0x09, 0x31,
        0x16, 0x00, 0x00, 0x26, (byte) 0xFF, 0x7F,
        0x36, 0x00, 0x00, 0x46, (byte) 0xFF, 0x7F,
        0x55, 0x00, 0x65, 0x00, 0x75, 0x10,
        (byte) 0x95, 0x02, (byte) 0x81, 0x02, (byte) 0xC0,
        0x09, 0x22, (byte) 0xA1, 0x02,
        0x09, 0x42, 0x09, 0x32, 0x15, 0x00, 0x25, 0x01,
        0x75, 0x01, (byte) 0x95, 0x02, (byte) 0x81, 0x02,
        0x75, 0x06, (byte) 0x95, 0x01, (byte) 0x81, 0x03,
        0x09, 0x51, 0x15, 0x01, 0x25, 0x05,
        0x75, 0x08, (byte) 0x95, 0x01, (byte) 0x81, 0x02,
        0x05, 0x01, 0x09, 0x30, 0x09, 0x31,
        0x16, 0x00, 0x00, 0x26, (byte) 0xFF, 0x7F,
        0x36, 0x00, 0x00, 0x46, (byte) 0xFF, 0x7F,
        0x55, 0x00, 0x65, 0x00, 0x75, 0x10,
        (byte) 0x95, 0x02, (byte) 0x81, 0x02, (byte) 0xC0,
        0x05, 0x0D, 0x09, 0x54, 0x15, 0x00, 0x25, 0x05,
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
    private int lastQueuedTouchCount;
    private volatile boolean gamepadMode = false;
    private volatile boolean profileSwitchPending = false;
    private volatile boolean registrationRequested = false;
    private volatile boolean registeredProfileGamepad = false;
    private byte[] latestGamepadReport = new byte[] {
            0, 0, 8, (byte) 128, (byte) 128, (byte) 128, (byte) 128, 0, 0
    };

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
        BluetoothHidDeviceAppSdpSettings sdp = new BluetoothHidDeviceAppSdpSettings(
                pad ? "OfflineMirror Gamepad" : "OfflineMirror Touch HID",
                pad ? "OfflineMirror standalone Bluetooth gamepad"
                    : "OfflineMirror standalone five-contact touchscreen",
                "OfflineMirror", subclass,
                pad ? GAMEPAD_REPORT_DESCRIPTOR : TOUCH_REPORT_DESCRIPTOR);
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
            if (registeredProfileGamepad != gamepadMode) {
                profileSwitchPending = true;
                if (hidDevice == null || !hidDevice.unregisterApp()) {
                    profileSwitchPending = false;
                    registered = false;
                    gamepadMode = registeredProfileGamepad;
                    status("Profil Bluetooth gagal diganti; mode sebelumnya dipertahankan");
                }
                return;
            }
            if (target == null) {
                status("Jaringan 2 • pair OfflineMirror " +
                        (gamepadMode ? "Gamepad" : "Touch HID") + " dari HP Host");
            } else {
                status("Jaringan 2 • menyambungkan " +
                        (gamepadMode ? "Gamepad" : "Touch HID") + "...");
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
            if (hidDevice != null && type == BluetoothHidDevice.REPORT_TYPE_INPUT) {
                try {
                    if (id == REPORT_ID) {
                        hidDevice.replyReport(device, type, id,
                                gamepadMode ? neutralReport() : neutralTouchReport());
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
        // The overlay visibility is controlled by ViewerActivity. Do not gate reports
        // on a second mode flag here: that flag can lag a UI transition and silently
        // discard every button event after switching from Touch HID to Gamepad.
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
                        () -> sendReportNow(REPORT_ID, report));
            } else {
                pendingGamepadReport = report;
                reportHandler.removeCallbacks(sendLatestGamepadReport);
                reportHandler.postDelayed(sendLatestGamepadReport, 8L);
            }
        }
    }

    /** Send up to five simultaneous absolute HID touchscreen contacts. */
    public void sendTouchContacts(int[] contactIds, float[] xs, float[] ys) {
        if (!connected || reportHandler == null || gamepadMode) return;
        byte[] report = buildTouchReport(contactIds, xs, ys);
        int count = contactIds == null ? 0 : Math.min(5, contactIds.length);
        synchronized (reportLock) {
            boolean edge = count != lastQueuedTouchCount;
            lastQueuedTouchCount = count;
            if (edge) {
                pendingTouchReport = null;
                reportHandler.removeCallbacks(sendLatestTouchReport);
                reportHandler.post(() -> sendReportNow(REPORT_ID, report));
            } else {
                pendingTouchReport = report;
                reportHandler.removeCallbacks(sendLatestTouchReport);
                reportHandler.postDelayed(sendLatestTouchReport, 6L);
            }
        }
    }

    public void sendTouchPoint(boolean pressed, float x, float y) {
        sendTouchContacts(pressed ? new int[]{1} : new int[0],
                pressed ? new float[]{x} : new float[0],
                pressed ? new float[]{y} : new float[0]);
    }

    private byte[] buildTouchReport(int[] contactIds, float[] xs, float[] ys) {
        byte[] report = new byte[31];
        int count = 0;
        for (int slot = 0; slot < 5; slot++) {
            int contactId = slot + 1;
            int source = -1;
            if (contactIds != null) {
                for (int i = 0; i < Math.min(5, contactIds.length); i++) {
                    if (contactIds[i] == contactId) { source = i; break; }
                }
            }
            int offset = slot * 6;
            report[offset] = (byte) (source >= 0 ? 0x03 : 0x00);
            report[offset + 1] = (byte) contactId;
            int px = source >= 0 && xs != null && source < xs.length
                    ? toTouchCoordinate(xs[source]) : 0;
            int py = source >= 0 && ys != null && source < ys.length
                    ? toTouchCoordinate(ys[source]) : 0;
            report[offset + 2] = (byte) (px & 0xFF);
            report[offset + 3] = (byte) ((px >>> 8) & 0xFF);
            report[offset + 4] = (byte) (py & 0xFF);
            report[offset + 5] = (byte) ((py >>> 8) & 0xFF);
            if (source >= 0) count++;
        }
        report[30] = (byte) count;
        return report;
    }

    private int toTouchCoordinate(float value) {
        if (Float.isNaN(value) || Float.isInfinite(value)) value = 0f;
        return Math.max(0, Math.min(32767,
                Math.round(Math.max(0f, Math.min(1f, value)) * 32767f)));
    }

    private byte[] neutralTouchReport() {
        byte[] report = new byte[31];
        for (int slot = 0; slot < 5; slot++) report[slot * 6 + 1] = (byte) (slot + 1);
        return report;
    }

    /**
     * Wi-Fi (Network 1) carries mirroring only. Bluetooth (Network 2) registers
     * one standalone Touch HID or Gamepad profile at a time.
     */
    public void setInputMode(boolean useGamepad) {
        if (gamepadMode == useGamepad) return;
        boolean oldMode = gamepadMode;
        BluetoothHidDevice device = hidDevice;
        BluetoothDevice receiver = target;
        if (connected && device != null && receiver != null) {
            try {
                device.sendReport(receiver, REPORT_ID,
                        oldMode ? neutralReport() : neutralTouchReport());
            } catch (Exception error) {
                Log.w(TAG, "Neutral report before profile switch failed", error);
            }
        }
        gamepadMode = useGamepad;
        resetQueuedState();
        if (device == null || !registered) {
            status("Mode disiapkan: Bluetooth " + (useGamepad ? "Gamepad" : "Touch HID"));
            return;
        }
        profileSwitchPending = true;
        try {
            if (connected && receiver != null) device.disconnect(receiver);
            if (!device.unregisterApp()) {
                profileSwitchPending = false;
                gamepadMode = oldMode;
                status("Gagal mengganti profil Bluetooth; mode lama dipertahankan");
                return;
            }
            connected = false;
            status("Jaringan 2 • mengganti profil ke " +
                    (useGamepad ? "Bluetooth Gamepad" : "Bluetooth Touch HID") + "...");
        } catch (SecurityException denied) {
            profileSwitchPending = false;
            gamepadMode = oldMode;
            status("Izin Bluetooth Connect diperlukan untuk mengganti profil");
        } catch (Exception error) {
            profileSwitchPending = false;
            gamepadMode = oldMode;
            status("Gagal mengganti profil Bluetooth: " + error.getMessage());
            Log.e(TAG, "Profile switch failed", error);
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
        sendReportNow(REPORT_ID, gamepadMode ? neutralReport() : neutralTouchReport());
    }

    private void sendPendingTouchReport() {
        synchronized (reportLock) {
            byte[] report = pendingTouchReport;
            pendingTouchReport = null;
            sendReportNow(REPORT_ID, report);
        }
    }

    private void sendPendingGamepadReport() {
        synchronized (reportLock) {
            byte[] report = pendingGamepadReport;
            pendingGamepadReport = null;
            // Serialize send with sendState so an older axis-only report cannot
            // race a D-pad release and be delivered after the newer neutral state.
            sendReportNow(REPORT_ID, report);
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
                if (reportId == REPORT_ID) {
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
            lastQueuedTouchCount = 0;
            pendingTouchReport = null;
            latestGamepadReport = neutralReport();

            if (reportHandler != null) {
                reportHandler.removeCallbacksAndMessages(null);
            }
        }
    }

    public void stop() {
        try {
            if (connected && hidDevice != null && target != null) {
                hidDevice.sendReport(target, REPORT_ID,
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
