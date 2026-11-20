package com.offlinemirror.app;

import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothHidDevice;
import android.bluetooth.BluetoothHidDeviceAppSdpSettings;
import android.bluetooth.BluetoothProfile;
import android.content.Intent;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executor;

/**
 * Bluetooth Classic HID gamepad peripheral.
 *
 * This makes the Viewer phone appear as a real standard game controller to
 * the Host phone. The mirror itself remains on the local Wi-Fi connection.
 */
public final class BluetoothHidGamepadApi28
        implements GamepadController, BluetoothProfile.ServiceListener {
    private static final int REPORT_ID = 1;

    // 16 buttons plus four signed 8-bit axes (X, Y, Rx, Ry).
    private static final byte[] REPORT_DESCRIPTOR = new byte[] {
            0x05, 0x01,             // Usage Page (Generic Desktop)
            0x09, 0x05,             // Usage (Game Pad)
            (byte) 0xA1, 0x01,      // Collection (Application)
            (byte) 0x85, 0x01,      //   Report ID (1)
            0x05, 0x09,             //   Usage Page (Button)
            0x19, 0x01,             //   Usage Minimum (Button 1)
            0x29, 0x10,             //   Usage Maximum (Button 16)
            0x15, 0x00,             //   Logical Minimum (0)
            0x25, 0x01,             //   Logical Maximum (1)
            (byte) 0x95, 0x10,      //   Report Count (16)
            0x75, 0x01,             //   Report Size (1)
            (byte) 0x81, 0x02,      //   Input (Data, Variable, Absolute)
            0x05, 0x01,             //   Usage Page (Generic Desktop)
            0x09, 0x30,             //   Usage (X)
            0x09, 0x31,             //   Usage (Y)
            0x09, 0x33,             //   Usage (Rx)
            0x09, 0x34,             //   Usage (Ry)
            0x15, (byte) 0x81,      //   Logical Minimum (-127)
            0x25, 0x7F,             //   Logical Maximum (127)
            0x75, 0x08,             //   Report Size (8)
            (byte) 0x95, 0x04,      //   Report Count (4)
            (byte) 0x81, 0x02,      //   Input (Data, Variable, Absolute)
            (byte) 0xC0             // End Collection
    };

    private final Activity activity;
    private final GamepadController.Listener listener;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Executor callbackExecutor = command -> mainHandler.post(command);

    private BluetoothAdapter adapter;
    private BluetoothHidDevice hid;
    private BluetoothDevice connectedDevice;
    private boolean registered;
    private boolean started;
    private boolean discoverableRequested;
    private byte[] lastReport = new byte[] {0, 0, 0, 0, 0, 0};

    public BluetoothHidGamepadApi28(Activity activity, GamepadController.Listener listener) {
        this.activity = activity;
        this.listener = listener;
    }

    public void start() {
        if (Build.VERSION.SDK_INT < 28) {
            postStatus("Bluetooth Gamepad HID memerlukan Android 9 atau lebih baru.");
            return;
        }
        if (started) {
            postStatus(registered
                    ? "Gamepad terdaftar. Pasangkan HP Host melalui Bluetooth."
                    : "Sedang menyiapkan Bluetooth Gamepad...");
            return;
        }

        adapter = BluetoothAdapter.getDefaultAdapter();
        if (adapter == null) {
            postStatus("Perangkat ini tidak menyediakan Bluetooth.");
            return;
        }
        if (!adapter.isEnabled()) {
            postStatus("Aktifkan Bluetooth pada HP Viewer terlebih dahulu.");
            return;
        }

        started = true;
        postStatus("Meminta layanan Bluetooth HID...");
        boolean requested = adapter.getProfileProxy(
                activity, this, BluetoothProfile.HID_DEVICE);
        if (!requested) {
            started = false;
            postStatus("Bluetooth HID Gamepad tidak didukung oleh sistem HP Viewer.");
        }
    }

    @Override
    public void onServiceConnected(int profile, BluetoothProfile proxy) {
        if (profile != BluetoothProfile.HID_DEVICE
                || !(proxy instanceof BluetoothHidDevice)) {
            postStatus("Layanan Bluetooth Gamepad tidak tersedia.");
            return;
        }

        hid = (BluetoothHidDevice) proxy;
        BluetoothHidDeviceAppSdpSettings settings =
                new BluetoothHidDeviceAppSdpSettings(
                        "OfflineMirror Gamepad",
                        "OfflineMirror virtual game controller",
                        "OfflineMirror",
                        BluetoothHidDevice.SUBCLASS2_GAMEPAD,
                        REPORT_DESCRIPTOR);

        boolean accepted = hid.registerApp(
                settings, null, null, callbackExecutor, callback);
        postStatus(accepted
                ? "Mendaftarkan OfflineMirror Gamepad..."
                : "Sistem menolak pendaftaran Bluetooth Gamepad.");
    }

    @Override
    public void onServiceDisconnected(int profile) {
        hid = null;
        registered = false;
        connectedDevice = null;
        postStatus("Layanan Bluetooth Gamepad terputus.");
    }

    private final BluetoothHidDevice.Callback callback = new BluetoothHidDevice.Callback() {
        @Override
        public void onAppStatusChanged(BluetoothDevice pluggedDevice, boolean isRegistered) {
            registered = isRegistered;
            if (pluggedDevice != null) connectedDevice = pluggedDevice;

            if (isRegistered) {
                postStatus("Gamepad terdaftar. Mengaktifkan mode dapat ditemukan...");
                requestDiscoverable();
            } else {
                connectedDevice = null;
                postStatus("Registrasi Bluetooth Gamepad belum aktif.");
            }
        }

        @Override
        public void onConnectionStateChanged(BluetoothDevice device, int state) {
            if (state == BluetoothProfile.STATE_CONNECTED) {
                connectedDevice = device;
                lastReport = neutralReport();
                sendLastReport();
                postStatus("Bluetooth Gamepad TERHUBUNG ke " + safeName(device) + ".");
            } else if (state == BluetoothProfile.STATE_DISCONNECTED) {
                if (connectedDevice != null && connectedDevice.equals(device)) {
                    connectedDevice = null;
                }
                postStatus("Gamepad terputus. Pilih ulang HP Host dari daftar perangkat.");
            } else if (state == BluetoothProfile.STATE_CONNECTING) {
                postStatus("Menghubungkan Bluetooth Gamepad ke " + safeName(device) + "...");
            }
        }

        @Override
        public void onGetReport(BluetoothDevice device, byte type, byte id, int bufferSize) {
            if (hid == null || type != BluetoothHidDevice.REPORT_TYPE_INPUT || id != REPORT_ID) {
                return;
            }
            byte[] data = lastReport.clone();
            if (bufferSize >= 0 && bufferSize < data.length) {
                byte[] shortened = new byte[bufferSize];
                System.arraycopy(data, 0, shortened, 0, shortened.length);
                data = shortened;
            }
            try {
                hid.replyReport(device, type, id, data);
            } catch (SecurityException error) {
                postStatus("Izin Bluetooth tidak tersedia untuk membalas laporan gamepad.");
            }
        }

        @Override
        public void onVirtualCableUnplug(BluetoothDevice device) {
            connectedDevice = null;
            postStatus("Koneksi gamepad dilepas dari HP Host.");
        }
    };

    public void requestDiscoverable() {
        if (discoverableRequested || activity.isFinishing()) return;
        discoverableRequested = true;
        mainHandler.post(() -> {
            try {
                Intent intent = new Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE);
                intent.putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 180);
                activity.startActivityForResult(intent, REQUEST_DISCOVERABLE);
            } catch (Exception error) {
                discoverableRequested = false;
                postStatus("Tidak dapat membuka izin agar Gamepad terlihat: "
                        + error.getMessage());
            }
        });
    }

    @Override
    public List<BluetoothDevice> getBondedDevices() {
        List<BluetoothDevice> devices = new ArrayList<>();
        if (adapter == null) adapter = BluetoothAdapter.getDefaultAdapter();
        if (adapter == null || !adapter.isEnabled()) return devices;
        try {
            Set<BluetoothDevice> bonded = adapter.getBondedDevices();
            if (bonded != null) devices.addAll(bonded);
        } catch (SecurityException error) {
            postStatus("Izin Bluetooth diperlukan untuk membaca daftar perangkat.");
        }
        return devices;
    }

    @Override
    public void connect(BluetoothDevice device) {
        if (hid == null || !registered || device == null) {
            postStatus("Gamepad belum terdaftar. Aktifkan Bluetooth Gamepad dahulu.");
            return;
        }
        try {
            postStatus("Meminta koneksi ke " + safeName(device) + "...");
            boolean accepted = hid.connect(device);
            if (!accepted) {
                postStatus("Koneksi ditolak. Pastikan HP Host sudah dipasangkan.");
            }
        } catch (SecurityException error) {
            postStatus("Izin Bluetooth tidak tersedia untuk menyambungkan perangkat.");
        }
    }

    @Override
    public boolean isRegistered() {
        return registered && hid != null;
    }

    @Override
    public boolean isConnected() {
        return isRegistered() && connectedDevice != null;
    }

    @Override
    public boolean sendState(int buttons, float leftX, float leftY) {
        lastReport = new byte[] {
                (byte) (buttons & 0xFF),
                (byte) ((buttons >>> 8) & 0xFF),
                toAxis(leftX),
                toAxis(leftY),
                0,
                0
        };
        return sendLastReport();
    }

    private boolean sendLastReport() {
        if (!isConnected()) return false;
        try {
            return hid.sendReport(connectedDevice, REPORT_ID, lastReport.clone());
        } catch (SecurityException error) {
            postStatus("Izin Bluetooth dicabut. Aktifkan ulang izin Bluetooth.");
            return false;
        }
    }

    private byte[] neutralReport() {
        return new byte[] {0, 0, 0, 0, 0, 0};
    }

    private byte toAxis(float value) {
        float safe = Math.max(-1f, Math.min(1f, value));
        return (byte) Math.round(safe * 127f);
    }

    private String safeName(BluetoothDevice device) {
        if (device == null) return "HP Host";
        try {
            String name = device.getName();
            return (name == null || name.trim().isEmpty()) ? "HP Host" : name;
        } catch (SecurityException error) {
            return "HP Host";
        }
    }

    private void postStatus(String value) {
        mainHandler.post(() -> {
            if (listener != null) listener.onStatus(value);
        });
    }

    @Override
    public void close() {
        if (hid != null) {
            try {
                if (registered) hid.unregisterApp();
                adapter.closeProfileProxy(BluetoothProfile.HID_DEVICE, hid);
            } catch (Exception ignored) {
            }
        }
        hid = null;
        connectedDevice = null;
        registered = false;
        started = false;
    }
}
