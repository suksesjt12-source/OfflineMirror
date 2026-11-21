# OfflineMirror 1.42.0

OfflineMirror mirrors an Android display to another Android phone over the same private Wi-Fi or hotspot. Internet access is not required.

## Setup

1. Install OfflineMirror on both Android phones.
2. Connect both phones to the same Wi-Fi network or hotspot.
3. On the Host, tap **START SCREEN SHARING** and approve screen capture.
4. On the Host, tap **SHOW PAIRING QR**.
5. On the Viewer, scan the QR and connect to the Host.
6. Enable Bluetooth on both phones. In Viewer, tap **PAIR BT**.
7. Pair the device named **OfflineMirror Touchscreen** from the Host Bluetooth settings. Keep Viewer open while using the connection.

## Bluetooth HID touchscreen and gamepad

- Bluetooth HID advertises the standard gamepad collection and an experimental HID Digitizer / Touch Screen collection.
- The touchscreen report uses absolute X/Y coordinates and one contact identifier. Tap and drag with one finger on the mirrored video area.
- Touch reports and gamepad reports are sent directly over Bluetooth HID. The former Wi-Fi Direct Touch packet and Android Accessibility gesture service have been removed.
- HID Touch Screen support is not consistent across Android versions and vendor firmware. Some Host devices may pair but ignore the touchscreen collection, or may reject the HID device. This mode is experimental and has not been verified on every Android model.
- No root, Accessibility service, mouse pointer, or cursor emulation is used for this input path.
- The gamepad supports standard Android button, hat-switch, stick and trigger usages, including L3/R3 and analog triggers.
- LAYOUT moves each gamepad button, D-pad direction and analog stick independently. Each position is saved locally.
- D-pad hit targets are spaced apart so adjacent directions cannot steal each other's touch.
- Gamepad button pointers are reconciled against active MotionEvent pointers. A final pointer-up/cancel sends a neutral state, and queued axis reports are serialized with button edges to avoid stale inputs.
- A focus loss, gamepad overlay hide, or activity shutdown sends neutral controller state.

## Automatic mapping and compatibility

Android's controller usage table maps A/B/X/Y to Button usages 1/2/4/5, L1/R1 to usages 7/8, the D-pad to the hat switch, the left stick to X/Y, the right stick to Z/Rz, and the triggers to Simulation Controls C5/C4. Compatible games and emulators can receive these standard Android input events. Individual games can still use a custom mapping screen.

## HUD

Use LAYOUT to enter edit mode, then drag each gamepad button, D-pad direction, or analog stick independently. Tap SELESAI when finished. Each position is saved automatically. Use HUD − to hide the status area and toolbar. HUD + restores the controls. The setting is remembered.

## Network channels

- Video: TCP port 48888 (H.264)
- Audio: TCP port 48889 (optional; Android 10+ and capture-compatible apps)
- Utility commands: TCP port 48890 (authenticated clipboard, volume, media and ping only)
- Wi-Fi does not carry touch injection or gamepad input. Touchscreen and gamepad reports use Bluetooth HID.

## Compatibility notes

- Screen mirroring: Android 5.0+
- Bluetooth HID Device mode: Android 9+ and vendor HID Device support on the Viewer
- Experimental HID Touch Screen input: Android Host support depends on the Bluetooth HID Host implementation and vendor firmware; not guaranteed
- Audio playback capture: Android 10+ and capture-compatible apps


## Automatic emulator mode, navigation and floating controls

- Enable **Auto Detect: emulator aktif → otomatis Gamepad** on the Host and grant OfflineMirror **Usage Access** in Android Settings. The Host checks foreground app events locally and tells the Viewer to show the gamepad overlay for recognized emulator packages.
- Recognized package-name markers include PPSSPP, Dolphin, RetroArch, AetherSX2/NetherSX2, Citra/Lime3DS, Yuzu/Sudachi, Winlator, DuckStation, Lemuroid, DraStic and GameHub. Some vendor builds use different package names, so detection is not guaranteed for every emulator.
- The detector reads foreground package identifiers only. It does not collect screen contents or send usage data to an internet service. Detection requires Android Usage Access and can vary by firmware.
- Enable **OfflineMirror Home dan Kembali** in Android Accessibility Settings on the Host. The Viewer toolbar's **BACK** and **HOME** buttons send global navigation requests over the authenticated local control channel.
- **FLOAT** on the Viewer opens a draggable overlay panel with BACK and HOME buttons. Android 6.0+ requires the Viewer to be allowed to display over other apps. The panel runs as a visible foreground service and can be closed with × or by closing the Viewer session.
- Home/Back remains unavailable until the Host Accessibility service is explicitly enabled by the user. Floating controls relay actions through the active Viewer session, so the Viewer session must remain alive.
- Automatic switching changes which input overlay is visible; it does not change Bluetooth pairing or inject touch events.
