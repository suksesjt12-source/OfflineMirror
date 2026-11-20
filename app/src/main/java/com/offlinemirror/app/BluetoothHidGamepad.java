package com.offlinemirror.app;

import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothHidDevice;
import android.bluetooth.BluetoothHidDeviceAppSdpSettings;
import android.bluetooth.BluetoothProfile;
import android.content.Context;
import android.os.Build;
import android.util.Log;

import java.util.concurrent.Executor;

/** Optional Classic Bluetooth HID gamepad peripheral for Android 9+ devices. */
public final class BluetoothHidGamepad {
    public interface StatusListener { void onStatus(String message); }
    private static final String TAG = "OfflineMirror-BluetoothHID";
    private static final int REPORT_ID = 1;
    private static final byte[] REPORT_DESCRIPTOR = new byte[] {
            0x05, 0x01, 0x09, 0x05, (byte) 0xA1, 0x01,
            (byte) 0x85, 0x01,
            0x05, 0x09, 0x19, 0x01, 0x29, 0x0E,
            0x15, 0x00, 0x25, 0x01, 0x75, 0x01, (byte) 0x95, 0x0E,
            (byte) 0x81, 0x02, 0x75, 0x02, (byte) 0x95, 0x01, (byte) 0x81, 0x03,
            0x05, 0x01, 0x09, 0x39, 0x15, 0x00, 0x25, 0x08,
            0x35, 0x00, 0x46, 0x3B, 0x01, 0x65, 0x14,
            0x75, 0x04, (byte) 0x95, 0x01, (byte) 0x81, 0x42,
            0x75, 0x04, (byte) 0x95, 0x01, (byte) 0x81, 0x03,
            0x09, 0x30, 0x09, 0x31, 0x09, 0x32, 0x09, 0x35,
            0x15, 0x00, 0x26, (byte) 0xFF, 0x00, 0x75, 0x08,
            (byte) 0x95, 0x04, (byte) 0x81, 0x02, (byte) 0xC0
    };

    private final Context context;
    private final StatusListener listener;
    private BluetoothHidDevice hidDevice;
    private BluetoothDevice target;
    private boolean registered;
    private boolean connected;

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
            boolean requested = adapter.getProfileProxy(context, profileListener,
                    BluetoothProfile.HID_DEVICE);
            if (!requested) status("Bluetooth HID Device tidak didukung vendor HP ini");
            else status("Mengaktifkan profil Bluetooth HID...");
        } catch (SecurityException denied) {
            status("Izin Bluetooth tidak diberikan");
        } catch (Exception error) {
            status("Bluetooth HID gagal dibuka: " + error.getMessage());
            Log.e(TAG, "Could not open HID profile", error);
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
            status(accepted ? "Mendaftarkan Bluetooth Gamepad..." :
                    "Pendaftaran Bluetooth HID ditolak sistem");
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
            status("Bluetooth HID siap • menyambungkan ke Host...");
            try {
                if (target != null && hidDevice != null && !hidDevice.connect(target)) {
                    status("Koneksi ditolak • periksa pairing Host");
                }
            } catch (SecurityException denied) {
                status("Izin Bluetooth Connect diperlukan");
            }
        }

        @Override public void onConnectionStateChanged(BluetoothDevice device, int state) {
            connected = state == BluetoothProfile.STATE_CONNECTED;
            if (connected) target = device;
            if (state == BluetoothProfile.STATE_CONNECTED) {
                status("Bluetooth HID terhubung ke Host");
                sendState(0, 0f, 0f);
            } else if (state == BluetoothProfile.STATE_CONNECTING) {
                status("Bluetooth HID sedang menyambungkan...");
            } else if (state == BluetoothProfile.STATE_DISCONNECTED) {
                status("Bluetooth HID terputus • Wi-Fi tetap tersedia");
            }
        }

        @Override public void onGetReport(BluetoothDevice device, byte type, byte id, int bufferSize) {
            if (hidDevice != null && type == BluetoothHidDevice.REPORT_TYPE_INPUT) {
                try { hidDevice.replyReport(device, type, id, neutralReport()); }
                catch (SecurityException ignored) {}
            }
        }
    };

    public void sendState(int buttons, float axisX, float axisY) {
        if (!connected || hidDevice == null || target == null) return;
        try {
            byte[] report = new byte[7];
            report[0] = (byte) (buttons & 0xFF);
            report[1] = (byte) ((buttons >>> 8) & 0x3F);
            report[2] = (byte) hatValue(buttons);
            report[3] = toAxis(axisX);
            report[4] = toAxis(axisY);
            report[5] = (byte) ((buttons & (1 << 8)) != 0 ? 0xFF : 0x00);
            report[6] = (byte) ((buttons & (1 << 9)) != 0 ? 0xFF : 0x00);
            hidDevice.sendReport(target, REPORT_ID, report);
        } catch (SecurityException denied) {
            status("Izin Bluetooth Connect hilang");
        } catch (Exception error) {
            Log.w(TAG, "HID report failed", error);
        }
    }

    public void releaseState() { sendState(0, 0f, 0f); }

    public void stop() {
        try {
            releaseState();
            if (hidDevice != null) {
                if (registered) hidDevice.unregisterApp();
                android.bluetooth.BluetoothAdapter adapter =
                        android.bluetooth.BluetoothAdapter.getDefaultAdapter();
                if (adapter != null) adapter.closeProfileProxy(BluetoothProfile.HID_DEVICE, hidDevice);
            }
        } catch (Exception error) {
            Log.w(TAG, "Error stopping HID controller", error);
        } finally {
            registered = false;
            connected = false;
            hidDevice = null;
            target = null;
        }
    }

    private byte[] neutralReport() { return new byte[]{0, 0, 8, (byte) 127, (byte) 127, 0, 0}; }

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

    private void status(String message) {
        if (listener != null) listener.onStatus(message);
    }
}
