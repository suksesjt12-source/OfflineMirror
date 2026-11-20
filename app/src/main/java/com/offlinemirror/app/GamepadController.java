package com.offlinemirror.app;

import android.bluetooth.BluetoothDevice;
import java.util.List;

/** Version-neutral contract; Bluetooth HID implementation is loaded only on API 28+. */
public interface GamepadController {
    int REQUEST_DISCOVERABLE = 7381;

    interface Listener {
        void onStatus(String status);
    }

    void start();
    boolean isRegistered();
    boolean isConnected();
    List<BluetoothDevice> getBondedDevices();
    void connect(BluetoothDevice device);
    boolean sendState(int buttons, float leftX, float leftY);
    void close();
}
