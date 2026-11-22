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
    public enum InputMode { TOUCH, GAMEPAD, COMPOSITE }

    public interface StatusListener {
        void onStatus(String message);
    }

    private static final String TAG = "OfflineMirror-BluetoothHID";
    private static final int GAMEPAD_REPORT_ID = 1;
    private static final int TOUCH_REPORT_ID = 2;

// Standard gamepad application collection: 16 buttons, a real 4-bit hat
// switch (D-pad), and four 8-bit axes. Report ID 1 is always 7 bytes.
private static final byte[] GAMEPAD_REPORT_DESCRIPTOR = new byte[] {
        0x05, 0x01, 0x09, 0x05, (byte) 0xA1, 0x01,
        (byte) 0x85, 0x01,
        0x05, 0x09, 0x19, 0x01, 0x29, 0x10,
        0x15, 0x00, 0x25, 0x01, 0x75, 0x01,
        (byte) 0x95, 0x10, (byte) 0x81, 0x02,
        0x05, 0x01, 0x09, 0x39,
        0x15, 0x00, 0x25, 0x07, 0x35, 0x00, 0x46, 0x3B, 0x01,
        0x65, 0x14, 0x75, 0x04, (byte) 0x95, 0x01, (byte) 0x81, 0x42,
        0x75, 0x04, (byte) 0x95, 0x01, (byte) 0x81, 0x03,
        0x05, 0x01, 0x09, 0x30, 0x09, 0x31, 0x09, 0x33, 0x09, 0x34,
        0x15, 0x00, 0x26, (byte) 0xFF, 0x00, 0x75, 0x08,
        (byte) 0x95, 0x04, (byte) 0x81, 0x02,
        (byte) 0xC0,
};

// Five-contact HID Digitizer Touch Screen descriptor (Report ID 2).
    private static final byte[] TOUCH_REPORT_HEADER = new byte[] {
            0x05, 0x0D, 0x09, 0x04, (byte) 0xA1, 0x01, (byte) 0x85, 0x02
    };
    private static final byte[] TOUCH_FINGER_COLLECTION = new byte[] {
            0x09, 0x22, (byte) 0xA1, 0x02,
            0x09, 0x42, 0x09, 0x32, 0x09, 0x47,
            0x15, 0x00, 0x25, 0x01, 0x75, 0x01,
            (byte) 0x95, 0x03, (byte) 0x81, 0x02,
            0x75, 0x05, (byte) 0x95, 0x01, (byte) 0x81, 0x03,
            0x09, 0x51, 0x15, 0x00, 0x26, (byte) 0xFF, 0x00,
            0x75, 0x08, (byte) 0x95, 0x01, (byte) 0x81, 0x02,
            0x05, 0x01, 0x09, 0x30, 0x09, 0x31,
            0x16, 0x00, 0x00, 0x26, (byte) 0xFF, 0x7F,
            0x36, 0x00, 0x00, 0x46, (byte) 0xFF, 0x7F,
            0x55, 0x00, 0x65, 0x00,
            0x75, 0x10, (byte) 0x95, 0x02, (byte) 0x81, 0x02,
            (byte) 0xC0
    };
    private static final byte[] TOUCH_REPORT_FOOTER = new byte[] {
            0x05, 0x0D, 0x09, 0x54,
            0x15, 0x00, 0x25, 0x05, 0x75, 0x08,
            (byte) 0x95, 0x01, (byte) 0x81, 0x02,
            0x09, 0x55, 0x15, 0x01, 0x25, 0x05,
            0x75, 0x08, (byte) 0x95, 0x01, (byte) 0xB1, 0x02,
            (byte) 0xC0
    };
    private static byte[] createTouchReportDescriptor() {
        byte[] result = new byte[TOUCH_REPORT_HEADER.length
                + TOUCH_FINGER_COLLECTION.length * 5 + TOUCH_REPORT_FOOTER.length];
        int offset = 0;
        System.arraycopy(TOUCH_REPORT_HEADER, 0, result, offset,
                TOUCH_REPORT_HEADER.length);
        offset += TOUCH_REPORT_HEADER.length;
        for (int i = 0; i < 5; i++) {
            System.arraycopy(TOUCH_FINGER_COLLECTION, 0, result, offset,
                    TOUCH_FINGER_COLLECTION.length);
            offset += TOUCH_FINGER_COLLECTION.length;
        }
        System.arraycopy(TOUCH_REPORT_FOOTER, 0, result, offset,
                TOUCH_REPORT_FOOTER.length);
        return result;
    }
    private static final byte[] TOUCH_REPORT_DESCRIPTOR = createTouchReportDescriptor();

    private static final byte[] COMPOSITE_REPORT_DESCRIPTOR =
            combineDescriptors(GAMEPAD_REPORT_DESCRIPTOR, TOUCH_REPORT_DESCRIPTOR);

    private static byte[] combineDescriptors(byte[] first, byte[] second) {
        byte[] combined = new byte[first.length + second.length];
        System.arraycopy(first, 0, combined, 0, first.length);
        System.arraycopy(second, 0, combined, first.length, second.length);
        return combined;
    }

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
    private int lastQueuedTouchSignature = -1;
    private boolean touchReportQueued;
    private boolean gamepadReportQueued;
    private volatile boolean gamepadMode = false;
    private volatile boolean profileSwitchPending = false;
    private volatile boolean registrationRequested = false;
    private volatile boolean registeredProfileGamepad = false;
    private volatile InputMode inputMode = InputMode.GAMEPAD;
    private volatile boolean stopping;
    private String originalAdapterName;
    private boolean adapterNameCaptured;
    private final Handler reconnectHandler = new Handler(android.os.Looper.getMainLooper());
    private final Runnable reconnectTask = () -> {
        BluetoothHidDevice device = hidDevice;
        BluetoothDevice receiver = target;
        if (stopping || connected || !registered || device == null || receiver == null) return;
        try {
            if (receiver.getBondState() == BluetoothDevice.BOND_BONDED) {
                status("Bluetooth terputus; mencoba menyambungkan kembali...");
                if (!device.connect(receiver)) status("Auto reconnect ditolak; sambungkan dari menu Bluetooth");
            }
        } catch (SecurityException denied) {
            status("Izin Bluetooth Connect diperlukan untuk auto reconnect");
        } catch (Exception error) {
            status("Auto reconnect gagal: " + error.getMessage());
        }
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
        start(targetDevice, InputMode.GAMEPAD);
    }

    public void start(BluetoothDevice targetDevice, InputMode mode) {
        stopping = false;
        inputMode = mode == null ? InputMode.GAMEPAD : mode;
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
            updateBluetoothDeviceName();
            ensureReportWorker();
            boolean requested = adapter.getProfileProxy(
                    context, profileListener, BluetoothProfile.HID_DEVICE);
            if (!requested) {
                status("Bluetooth HID Device tidak didukung firmware HP ini");
            } else {
                status("Mendaftarkan Bluetooth HID " + (inputMode == InputMode.COMPOSITE ? "Dual Composite..." : inputMode == InputMode.GAMEPAD ? "Gamepad..." : "Touch HID..."));
            }
        } catch (SecurityException denied) {
            status("Izin Bluetooth tidak diberikan");
        } catch (Exception error) {
            status("Bluetooth HID gagal dibuka: " + error.getMessage());
            Log.e(TAG, "Could not open HID profile", error);
        }
    }

    /**
     * The Android Bluetooth adapter has one global advertised name. Change it
     * to the active HID profile while OfflineMirror is running, then restore
     * the user's original phone name when the HID service stops.
     */
    private void updateBluetoothDeviceName() {
        try {
            android.bluetooth.BluetoothAdapter adapter =
                    android.bluetooth.BluetoothAdapter.getDefaultAdapter();
            if (adapter == null || !adapter.isEnabled()) return;
            if (!adapterNameCaptured) {
                originalAdapterName = adapter.getName();
                adapterNameCaptured = true;
            }
            String desired = inputMode == InputMode.COMPOSITE ? "Mirror HID"
                    : inputMode == InputMode.GAMEPAD ? "Gamepad" : "Touch";
            String current = adapter.getName();
            if (!desired.equals(current)) {
                if (!adapter.setName(desired)) {
                    status("Nama Bluetooth profil tidak dapat diubah oleh firmware");
                }
            }
        } catch (SecurityException denied) {
            status("Izin Bluetooth Connect diperlukan untuk mengganti nama profil");
        } catch (Exception error) {
            Log.w(TAG, "Could not update Bluetooth adapter name", error);
        }
    }

    private void restoreBluetoothDeviceName() {
        if (!adapterNameCaptured) return;
        try {
            android.bluetooth.BluetoothAdapter adapter =
                    android.bluetooth.BluetoothAdapter.getDefaultAdapter();
            if (adapter != null && adapter.isEnabled()
                    && originalAdapterName != null
                    && !originalAdapterName.equals(adapter.getName())) {
                adapter.setName(originalAdapterName);
            }
        } catch (Exception error) {
            Log.w(TAG, "Could not restore original Bluetooth adapter name", error);
        } finally {
            originalAdapterName = null;
            adapterNameCaptured = false;
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
        registrationRequested = true;
        boolean gamepad = inputMode == InputMode.GAMEPAD
                || inputMode == InputMode.COMPOSITE;
        byte[] descriptor = inputMode == InputMode.COMPOSITE ? COMPOSITE_REPORT_DESCRIPTOR
                : gamepad ? GAMEPAD_REPORT_DESCRIPTOR : TOUCH_REPORT_DESCRIPTOR;
        String deviceName = inputMode == InputMode.COMPOSITE ? "Mirror HID"
                : gamepad ? "Gamepad" : "Touch";
        String description = inputMode == InputMode.COMPOSITE
                ? "OfflineMirror Composite Gamepad and Touch HID"
                : gamepad ? "OfflineMirror Bluetooth Gamepad Controller"
                : "OfflineMirror Bluetooth Touchscreen";
        byte subclass = gamepad ? (byte) BluetoothHidDevice.SUBCLASS2_GAMEPAD : (byte) 0x00;
        BluetoothHidDeviceAppSdpSettings sdp = new BluetoothHidDeviceAppSdpSettings(
                deviceName, description, "OfflineMirror", subclass, descriptor);
        boolean ok = device.registerApp(sdp, null, null, context.getMainExecutor(), callback);
        if (!ok) registrationRequested = false;
        status(ok ? "Bluetooth " + (inputMode == InputMode.COMPOSITE ? "Dual HID Composite" : gamepad ? "Gamepad" : "Touch HID") + " didaftarkan"
                  : "Pendaftaran Bluetooth " + (inputMode == InputMode.COMPOSITE ? "Dual HID Composite" : gamepad ? "Gamepad" : "Touch HID") + " gagal");
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
            if (profileSwitchPending) {
                try {
                    if (hidDevice != null) hidDevice.unregisterApp();
                } catch (Exception error) {
                    profileSwitchPending = false;
                    status("Gagal menutup profil HID sebelumnya: " + error.getMessage());
                }
                return;
            }
            if (target == null) {
                status("Pair OfflineMirror " + (inputMode == InputMode.COMPOSITE ? "Dual HID Composite" : inputMode == InputMode.GAMEPAD ? "Gamepad" : "Touch HID") + " dari HP Host");
            } else {
                status("Menyambungkan OfflineMirror " + (inputMode == InputMode.COMPOSITE ? "Dual HID Composite" : inputMode == InputMode.GAMEPAD ? "Gamepad" : "Touch HID") + "...");
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
                status("Bluetooth " + (inputMode == InputMode.COMPOSITE ? "Dual HID Composite" : inputMode == InputMode.GAMEPAD ? "Gamepad" : "Touch HID") + " aktif dan siap");
                sendNeutralReport();
            } else if (state == BluetoothProfile.STATE_CONNECTING) {
                status("Bluetooth HID sedang menyambungkan...");
            } else if (state == BluetoothProfile.STATE_DISCONNECTED) {
                resetQueuedState();
                if (!stopping && registered && target != null) {
                    reconnectHandler.removeCallbacks(reconnectTask);
                    reconnectHandler.postDelayed(reconnectTask, 1200L);
                }
                status("Bluetooth " + (inputMode == InputMode.COMPOSITE ? "Dual HID Composite" : inputMode == InputMode.GAMEPAD ? "Gamepad" : "Touch HID")
                        + " terputus; sambungkan ulang dari Viewer");
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
                        hidDevice.replyReport(device, type, id, new byte[] { 5 });
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
        if ((inputMode != InputMode.GAMEPAD && inputMode != InputMode.COMPOSITE) || !connected || reportHandler == null) return;

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

    /** Five-contact absolute HID touchscreen input. */
    public void sendTouchContacts(int[] contactIds, float[] xs, float[] ys) {
        if ((inputMode != InputMode.TOUCH && inputMode != InputMode.COMPOSITE) || !connected || reportHandler == null) return;

        int count = Math.min(5, Math.min(contactIds == null ? 0 : contactIds.length,
                Math.min(xs == null ? 0 : xs.length, ys == null ? 0 : ys.length)));
        int signature = 0;
        for (int i = 0; i < count; i++) {
            int id = contactIds[i];
            if (id >= 0 && id < 31) signature |= (1 << id);
        }
        byte[] report = buildTouchReport(contactIds, xs, ys, count);

        synchronized (reportLock) {
            boolean edge = signature != lastQueuedTouchSignature;
            lastQueuedTouchSignature = signature;
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

    private byte[] buildTouchReport(int[] contactIds, float[] xs, float[] ys, int count) {
        byte[] report = new byte[31]; // Five contacts (6 bytes each) plus Contact Count.
        for (int i = 0; i < count; i++) {
            int base = i * 6;
            int px = toTouchCoordinate(xs[i]);
            int py = toTouchCoordinate(ys[i]);
            report[base] = 0x07;
            report[base + 1] = (byte) (contactIds[i] & 0xFF);
            report[base + 2] = (byte) (px & 0xFF);
            report[base + 3] = (byte) ((px >>> 8) & 0xFF);
            report[base + 4] = (byte) (py & 0xFF);
            report[base + 5] = (byte) ((py >>> 8) & 0xFF);
        }
        report[30] = (byte) count;
        return report;
    }

    private int toTouchCoordinate(float value) {
        if (Float.isNaN(value) || Float.isInfinite(value)) value = 0f;
        return Math.max(0, Math.min(32767,
                Math.round(Math.max(0f, Math.min(1f, value)) * 32767f)));
    }

    private byte[] neutralTouchReport() { return new byte[31]; }

    /** Select one independent Bluetooth HID personality per registration. */
    public void setInputMode(boolean showGamepadOverlay) {
        inputMode = showGamepadOverlay ? InputMode.GAMEPAD : InputMode.TOUCH;
    }

    /** Switches the active HID personality without opening a second HID proxy. */
    public void switchProfile(BluetoothDevice newTarget, InputMode newMode) {
        InputMode requestedMode = newMode == null ? InputMode.GAMEPAD : newMode;
        boolean sameTarget = target == null ? newTarget == null
                : target.equals(newTarget);
        if (inputMode == requestedMode && sameTarget && isConnected()) return;
        BluetoothDevice previousTarget = target;
        if (connected && hidDevice != null && previousTarget != null) {
            try {
                if (inputMode == InputMode.GAMEPAD || inputMode == InputMode.COMPOSITE)
                    hidDevice.sendReport(previousTarget, GAMEPAD_REPORT_ID, neutralReport());
                if (inputMode == InputMode.TOUCH || inputMode == InputMode.COMPOSITE)
                    hidDevice.sendReport(previousTarget, TOUCH_REPORT_ID, neutralTouchReport());
            } catch (Exception ignored) {}
        }
        target = newTarget;
        inputMode = requestedMode;
        updateBluetoothDeviceName();
        resetQueuedState();
        if (hidDevice == null) {
            start(newTarget, requestedMode);
            return;
        }
        profileSwitchPending = true;
        if (registered) {
            try {
                hidDevice.unregisterApp();
            } catch (Exception error) {
                profileSwitchPending = false;
                status("Tidak dapat mengganti profil HID: " + error.getMessage());
            }
        } else if (!registrationRequested) {
            profileSwitchPending = false;
            registerCurrentProfile();
        }
    }

    public boolean isConnected() {
        return connected && registered && hidDevice != null;
    }

    public String getActiveProfileName() {
        return inputMode == InputMode.COMPOSITE ? "Bluetooth Dual HID Composite"
                : "Bluetooth " + (inputMode == InputMode.GAMEPAD ? "Gamepad" : "Touch");
    }

    public boolean isGamepadMode() {
        return inputMode == InputMode.GAMEPAD;
    }

    private void sendNeutralReport() {
        if (inputMode == InputMode.GAMEPAD || inputMode == InputMode.COMPOSITE)
            sendReportNow(GAMEPAD_REPORT_ID, neutralReport());
        if (inputMode == InputMode.TOUCH || inputMode == InputMode.COMPOSITE)
            sendReportNow(TOUCH_REPORT_ID, neutralTouchReport());
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
                    String reportName = reportId == GAMEPAD_REPORT_ID ? "Gamepad" : "Touchscreen";
                    String profileName = inputMode == InputMode.COMPOSITE
                            ? "Dual HID Composite" : reportName;
                    status("Bluetooth stack tidak menerima laporan " + reportName
                            + " • profil " + profileName);
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
        byte[] report = new byte[7];
        // D-pad is encoded only in the HID hat-switch field below. Keeping its
        // overlay bits out of the button bitmap avoids duplicate/non-standard
        // directional buttons on Android hosts that expose both usages.
        int dpadMask = GamepadOverlayView.BUTTON_DPAD_UP
                | GamepadOverlayView.BUTTON_DPAD_DOWN
                | GamepadOverlayView.BUTTON_DPAD_LEFT
                | GamepadOverlayView.BUTTON_DPAD_RIGHT;
        int mask = buttons & 0xFFFF & ~dpadMask;
        report[0] = (byte) (mask & 0xFF);
        report[1] = (byte) ((mask >>> 8) & 0xFF);

        // HID hat values: up, up-right, right, down-right, down,
        // down-left, left, up-left; 8 means neutral/null.
        boolean up = (buttons & GamepadOverlayView.BUTTON_DPAD_UP) != 0;
        boolean down = (buttons & GamepadOverlayView.BUTTON_DPAD_DOWN) != 0;
        boolean left = (buttons & GamepadOverlayView.BUTTON_DPAD_LEFT) != 0;
        boolean right = (buttons & GamepadOverlayView.BUTTON_DPAD_RIGHT) != 0;
        int hat = 8;
        if (up && right && !down && !left) hat = 1;
        else if (right && !up && !down && !left) hat = 2;
        else if (down && right && !up && !left) hat = 3;
        else if (down && !up && !left && !right) hat = 4;
        else if (down && left && !up && !right) hat = 5;
        else if (left && !up && !down && !right) hat = 6;
        else if (up && left && !down && !right) hat = 7;
        else if (up && !down && !left && !right) hat = 0;
        report[2] = (byte) (hat & 0x0F);
        report[3] = toAxis(lx); // X
        report[4] = toAxis(ly); // Y
        report[5] = toAxis(rx); // Rx (right stick X)
        report[6] = toAxis(ry); // Ry (right stick Y)
        return report;
    }

    private byte[] neutralReport() {
        return new byte[] {
                0, 0, 8, (byte) 128, (byte) 128, (byte) 128, (byte) 128
        };
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
            lastQueuedTouchSignature = -1;
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
        stopping = true;
        reconnectHandler.removeCallbacks(reconnectTask);
        try {
            if (connected && hidDevice != null && target != null) {
                if (inputMode == InputMode.GAMEPAD || inputMode == InputMode.COMPOSITE)
                    hidDevice.sendReport(target, GAMEPAD_REPORT_ID, neutralReport());
                if (inputMode == InputMode.TOUCH || inputMode == InputMode.COMPOSITE)
                    hidDevice.sendReport(target, TOUCH_REPORT_ID, neutralTouchReport());
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
            restoreBluetoothDeviceName();
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
