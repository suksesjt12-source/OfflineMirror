package com.offlinemirror.app;

import android.bluetooth.BluetoothDevice;
import android.content.Context;

import java.util.concurrent.atomic.AtomicLong;

/**
 * App-level virtual input adapter manager.
 *
 * It exposes independent Gamepad and Touch routes while sharing one Android
 * Bluetooth HID peripheral/transport. It does not create multiple physical
 * Bluetooth adapters or independently pairable Bluetooth devices.
 */
public final class VirtualBluetoothAdapterManager {
    public interface StatusListener {
        void onStatus(String message);
    }

    private final Context context;
    private final StatusListener listener;
    private final AtomicLong gamepadReports = new AtomicLong();
    private final AtomicLong touchReports = new AtomicLong();

    private volatile BluetoothHidGamepad transport;
    private volatile boolean gamepadEnabled;
    private volatile boolean touchEnabled;
    private volatile BluetoothDevice target;
    private volatile String transportStatus = "Belum tersambung";

    public VirtualBluetoothAdapterManager(Context context, StatusListener listener) {
        this.context = context.getApplicationContext();
        this.listener = listener;
    }

    /**
     * Configures the two logical endpoints. Both enabled uses one composite HID
     * descriptor; enabling only one selects that endpoint's standalone descriptor.
     */
    public synchronized void configure(
            BluetoothDevice host, boolean enableGamepad, boolean enableTouch) {
        target = host;
        gamepadEnabled = enableGamepad;
        touchEnabled = enableTouch;

        if (!enableGamepad && !enableTouch) {
            BluetoothHidGamepad previous = transport;
            transport = null;
            if (previous != null) previous.stop();
            transportStatus = "Kedua profil virtual dinonaktifkan";
            publishStatus();
            return;
        }

        BluetoothHidGamepad.InputMode mode = resolveMode(enableGamepad, enableTouch);
        BluetoothHidGamepad current = transport;
        publishStatus();

        if (current == null) {
            current = new BluetoothHidGamepad(context, message -> {
                transportStatus = message;
                publishStatus();
            });
            transport = current;
            current.start(host, mode);
        } else {
            current.switchProfile(host, mode);
        }
    }

    private BluetoothHidGamepad.InputMode resolveMode(
            boolean enableGamepad, boolean enableTouch) {
        if (enableGamepad && enableTouch) return BluetoothHidGamepad.InputMode.COMPOSITE;
        return enableGamepad
                ? BluetoothHidGamepad.InputMode.GAMEPAD
                : BluetoothHidGamepad.InputMode.TOUCH;
    }

    public void sendGamepad(
            int buttons, float leftX, float leftY, float rightX, float rightY) {
        BluetoothHidGamepad current = transport;
        if (!gamepadEnabled || current == null) return;
        gamepadReports.incrementAndGet();
        current.sendState(buttons, leftX, leftY, rightX, rightY);
    }

    public void sendTouch(int[] contactIds, float[] xs, float[] ys) {
        BluetoothHidGamepad current = transport;
        if (!touchEnabled || current == null) return;
        touchReports.incrementAndGet();
        current.sendTouchContacts(contactIds, xs, ys);
    }

    public boolean isGamepadEnabled() {
        return gamepadEnabled;
    }

    public boolean isTouchEnabled() {
        return touchEnabled;
    }

    public boolean isConnected() {
        BluetoothHidGamepad current = transport;
        return current != null && current.isConnected();
    }

    public String getSummary() {
        String mode = gamepadEnabled && touchEnabled ? "Komposit Gamepad + Touch"
                : gamepadEnabled ? "Gamepad saja"
                : touchEnabled ? "Touch saja" : "Tidak aktif";
        return "VIRTUAL BLUETOOTH ADAPTER MANAGER\n"
                + "Virtual Gamepad: " + (gamepadEnabled ? "AKTIF" : "NONAKTIF")
                + " • input diteruskan: " + gamepadReports.get() + "\n"
                + "Virtual Touch: " + (touchEnabled ? "AKTIF" : "NONAKTIF")
                + " • input diteruskan: " + touchReports.get() + "\n"
                + "Mode HID: " + mode + "\n"
                + "Transport: satu adaptor Bluetooth fisik / satu koneksi HID bersama\n"
                + "Status: " + transportStatus + "\n\n"
                + "Kedua profil adalah endpoint logis di dalam OfflineMirror, bukan dua adaptor "
                + "Bluetooth Android yang terpisah. Untuk memastikan keduanya dikenali OS Host, "
                + "jalankan SCAN INPUT BLUETOOTH pada HP Host.";
    }

    private void publishStatus() {
        if (listener == null) return;
        String profiles = "Virtual Gamepad " + (gamepadEnabled ? "ON" : "OFF")
                + " • Virtual Touch " + (touchEnabled ? "ON" : "OFF");
        listener.onStatus(profiles + " • " + transportStatus);
    }

    public synchronized void stop() {
        BluetoothHidGamepad previous = transport;
        transport = null;
        gamepadEnabled = false;
        touchEnabled = false;
        if (previous != null) previous.stop();
        transportStatus = "Manager dihentikan";
        publishStatus();
    }
}
