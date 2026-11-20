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

/**
 * Bluetooth HID gamepad peripheral. It sends the latest controller state from
 * a dedicated display-priority worker so slow Bluetooth stack calls never block
 * the Viewer UI thread and stale stick reports are coalesced instead of queued.
 */
public final class BluetoothHidGamepad {
    public interface StatusListener { void onStatus(String message); }

    private static final String TAG = "OfflineMirror-BluetoothHID";
    private static final int REPORT_ID = 1;

    // 14 buttons + 4-bit hat + X/Y/Rx/Ry (two independent analog sticks).
    private static final byte[] REPORT_DESCRIPTOR = new byte[] {
            0x05, 0x01, 0x09, 0x05, (byte) 0xA1, 0x01,
            (byte) 0x85, 0x01,
            0x05, 0x09, 0x19, 0x01, 0x29, 0x0E,
            0x15, 0x00, 0x25, 0x01, 0x75, 0x01, (byte) 0x95, 0x0E,
            (byte) 0x81, 0x02,
            0x75, 0x02, (byte) 0x95, 0x01, (byte) 0x81, 0x03,
            0x05, 0x01, 0x09, 0x39, 0x15, 0x00, 0x25, 0x08,
            0x35, 0x00, 0x46, 0x3B, 0x01, 0x65, 0x14,
            0x75, 0x04, (byte) 0x95, 0x01, (byte) 0x81, 0x42,
            0x75, 0x04, (byte) 0x95, 0x01, (byte) 0x81, 0x03,
            0x09, 0x30, 0x09, 0x31, 0x09, 0x33, 0x09, 0x34,
            0x15, 0x00, 0x26, (byte) 0xFF, 0x00,
            0x75, 0x08, (byte) 0x95, 0x04, (byte) 0x81, 0x02,
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
    private byte[] pendingReport;
    private int lastQueuedButtons = -1;

    private final Runnable sendLatestReport = () -> {
        byte[] report;
        BluetoothHidDevice device;
        BluetoothDevice receiver;
        synchronized (reportLock) {
            report = pendingReport;
            pendingReport = null;
            device = hidDevice;
            receiver = target;
        }
        if (report == null || !connected || device == null || receiver == null) return;
        try {
            if (!device.sendReport(receiver, REPORT_ID, report)) {
                Log.w(TAG, "Bluetooth stack rejected HID input report");
            }
        } catch (SecurityException denied) {
            status("Izin Bluetooth Connect diperlukan");
        } catch (Exception error) {
            Log.w(TAG, "HID input report failed", error);
        }
    };

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
                status("Mengaktifkan profil Bluetooth HID...");
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
        @Override public void onServiceConnected(int profile, BluetoothProfile proxy) {
            if (profile != BluetoothProfile.HID_DEVICE
                    || !(proxy instanceof BluetoothHidDevice)) {
                status("Profil Bluetooth HID tidak tersedia");
                return;
            }
            hidDevice = (BluetoothHidDevice) proxy;
            BluetoothHidDeviceAppSdpSettings sdp =
                    new BluetoothHidDeviceAppSdpSettings(
                            "OfflineMirror Gamepad", "OfflineMirror Bluetooth Gamepad",
                            "OfflineMirror", BluetoothHidDevice.SUBCLASS2_GAMEPAD,
                            REPORT_DESCRIPTOR);
            Executor executor = context.getMainExecutor();
            boolean accepted = hidDevice.registerApp(sdp, null, null, executor, callback);
            status(accepted ? (target == null
                    ? "Mendaftarkan HID • siap dipair dari HP Host..."
                    : "Mendaftarkan Bluetooth Gamepad...")
                    : "Pendaftaran Bluetooth HID ditolak sistem");
        }

        @Override public void onServiceDisconnected(int profile) {
            connected = false;
            registered = false;
            hidDevice = null;
            status("Layanan Bluetooth HID terputus");
        }
    };

    private final BluetoothHidDevice.Callback callback = new BluetoothHidDevice.Callback() {
        @Override public void onAppStatusChanged(BluetoothDevice pluggedDevice, boolean isRegistered) {
            registered = isRegistered;
            if (!isRegistered) {
                connected = false;
                status("Bluetooth HID tidak terdaftar");
                return;
            }
            if (target == null) {
                status("Bluetooth HID siap • pair Viewer dari Setelan Bluetooth di HP Host");
            } else {
                status("Bluetooth HID siap • menyambungkan ke Host...");
                try {
                    if (hidDevice != null && !hidDevice.connect(target)) {
                        status("Koneksi ditolak • periksa pairing Bluetooth di Host");
                    }
                } catch (SecurityException denied) {
                    status("Izin Bluetooth Connect diperlukan");
                }
            }
        }

        @Override public void onConnectionStateChanged(BluetoothDevice device, int state) {
            connected = state == BluetoothProfile.STATE_CONNECTED;
            if (connected) {
                target = device;
                status("Bluetooth HID terhubung • dua stick aktif");
                sendState(0, 0f, 0f, 0f, 0f);
            } else if (state == BluetoothProfile.STATE_CONNECTING) {
                status("Bluetooth HID sedang menyambungkan...");
            } else if (state == BluetoothProfile.STATE_DISCONNECTED) {
                status("Bluetooth HID terputus • sambungkan ulang dari Viewer");
            }
        }

        @Override public void onGetReport(BluetoothDevice device, byte type, byte id, int bufferSize) {
            if (hidDevice != null && type == BluetoothHidDevice.REPORT_TYPE_INPUT) {
                try {
                    hidDevice.replyReport(device, type, id, neutralReport());
                } catch (SecurityException ignored) {
                    // The OS may revoke Bluetooth access while the Viewer is closing.
                }
            }
        }
    };

    /**
     * Latest-state transport: button transitions are sent immediately, while
     * continuous stick motion is coalesced to about 125 Hz. No stale queue builds up.
     */
    public void sendState(int buttons, float leftX, float leftY,
                          float rightX, float rightY) {
        if (!connected || reportHandler == null) return;
        byte[] report = buildReport(buttons, leftX, leftY, rightX, rightY);
        boolean buttonEdge = buttons != lastQueuedButtons;
        lastQueuedButtons = buttons;
        synchronized (reportLock) {
            pendingReport = report;
            reportHandler.removeCallbacks(sendLatestReport);
            if (buttonEdge) {
                reportHandler.post(sendLatestReport);
            } else {
                reportHandler.postDelayed(sendLatestReport, 8L);
            }
        }
    }

    public void releaseState() {
        sendState(0, 0f, 0f, 0f, 0f);
    }

    private byte[] buildReport(int buttons, float leftX, float leftY,
                               float rightX, float rightY) {
        byte[] report = new byte[7];
        report[0] = (byte) (buttons & 0xFF);
        report[1] = (byte) ((buttons >>> 8) & 0x3F);
        report[2] = (byte) hatValue(buttons);
        report[3] = toAxis(leftX);
        report[4] = toAxis(leftY);
        report[5] = toAxis(rightX);
        report[6] = toAxis(rightY);
        return report;
    }

    private byte[] neutralReport() {
        return new byte[]{0, 0, 8, (byte) 128, (byte) 128, (byte) 128, (byte) 128};
    }

    private int hatValue(int buttons) {
        boolean up = (buttons & (1 << 10)) != 0;
        boolean down = (buttons & (1 << 11)) != 0;
        boolean left = (buttons & (1 << 12)) != 0;
        boolean right = (buttons & (1 << 13)) != 0;
        if (up && right) return 2;
        if (right && down) return 4;
        if (down && left) return 6;
        if (left && up) return 8;
        if (up) return 1;
        if (right) return 3;
        if (down) return 5;
        if (left) return 7;
        return 8;
    }

    private byte toAxis(float value) {
        if (Float.isNaN(value) || Float.isInfinite(value)) value = 0f;
        int axis = Math.round((Math.max(-1f, Math.min(1f, value)) + 1f) * 127.5f);
        return (byte) Math.max(0, Math.min(255, axis));
    }

    public void stop() {
        try {
            if (connected && hidDevice != null && target != null) {
                hidDevice.sendReport(target, REPORT_ID, neutralReport());
            }
            if (hidDevice != null) {
                if (registered) hidDevice.unregisterApp();
                android.bluetooth.BluetoothAdapter adapter =
                        android.bluetooth.BluetoothAdapter.getDefaultAdapter();
                if (adapter != null) {
                    adapter.closeProfileProxy(BluetoothProfile.HID_DEVICE, hidDevice);
                }
            }
        } catch (Exception error) {
            Log.w(TAG, "Error stopping HID controller", error);
        } finally {
            connected = false;
            registered = false;
            hidDevice = null;
            target = null;
            synchronized (reportLock) {
                if (reportHandler != null) reportHandler.removeCallbacksAndMessages(null);
                if (reportThread != null) reportThread.quitSafely();
                reportHandler = null;
                reportThread = null;
                pendingReport = null;
                lastQueuedButtons = -1;
            }
        }
    }

    private void status(String message) {
        if (listener != null) listener.onStatus(message);
    }
}
