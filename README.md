# OfflineMirror 1.43.0

OfflineMirror mirrors an Android display to another Android phone over the same private Wi-Fi or hotspot. Internet access is not required.

## Setup

1. Install OfflineMirror on both Android phones.
2. Connect both phones to the same Wi-Fi network or hotspot.
3. On the Host, tap **START SCREEN SHARING** and approve screen capture.
4. On the Host, tap **SHOW PAIRING QR**.
5. On the Viewer, scan the QR and connect to the Host.
6. Enable Bluetooth on both phones. In Viewer, tap **PAIR BT**.
7. Pair the device named **OfflineMirror Controller** from the Host Bluetooth settings. Keep Viewer open while using the connection.

## Composite Bluetooth HID: Touchscreen, Gamepad and Home/Back

- One Bluetooth HID device advertises the standard Game Pad collection plus the experimental HID Digitizer / Touch Screen collection. Home and Back are Consumer usages AC Home and AC Back inside the same Game Pad collection; no second Bluetooth device or Accessibility service is used.
- The touchscreen report uses absolute X/Y coordinates and one contact identifier. Tap and drag with one finger on the mirrored video area.
- Touch reports, gamepad reports, and HOME/BACK key reports are sent over the same Bluetooth HID connection. Wi-Fi carries only the emulator-mode notification and utility features; HOME/BACK are not sent over Wi-Fi.
- HID Touch Screen support is not consistent across Android versions and vendor firmware. Some Host devices may pair but ignore the touchscreen collection, or may reject the HID device. This mode is experimental and has not been verified on every Android model.
- No root, Accessibility service, overlay permission, mouse pointer, or cursor emulation is used for this input path.
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
- Utility commands: TCP port 48890 (authenticated clipboard, volume, media, emulator-mode notifications and ping)
- Wi-Fi carries video/audio and utility signals. Touchscreen, Gamepad, HOME and BACK input reports all use the same Bluetooth HID connection.

## Compatibility notes

- Screen mirroring: Android 5.0+
- Bluetooth HID Device mode: Android 9+ and vendor HID Device support on the Viewer
- Experimental HID Touch Screen input: Android Host support depends on the Bluetooth HID Host implementation and vendor firmware; not guaranteed
- HOME/BACK Consumer usages: Android Host should map AC Home (0x0C/0x0223) to KEYCODE_HOME and AC Back (0x0C/0x0224) to KEYCODE_BACK; vendor compatibility can vary
- Audio playback capture: Android 10+ and capture-compatible apps

## Automatic input-mode switching and navigation

- Enable **Auto Detect: emulator aktif → otomatis Gamepad** on the Host and grant OfflineMirror **Usage Access** in Android Settings. The Host checks foreground app events locally and sends the input-mode state to the Viewer.
- Recognized package-name markers include PPSSPP, Dolphin, RetroArch, AetherSX2/NetherSX2, Citra/Lime3DS, Yuzu/Sudachi, Winlator, DuckStation, Lemuroid, DraStic and GameHub. Some vendor builds use different package names, so detection is not guaranteed for every emulator.
- The detector reads foreground package identifiers only. It does not collect screen contents or send usage data to an internet service. Detection requires Android Usage Access and can vary by firmware.
- The Viewer toolbar **BACK** and **HOME** buttons send standard AC Back / AC Home Consumer usages in the composite Bluetooth HID Game Pad report. Android maps these usages to KEYCODE_BACK and KEYCODE_HOME on compatible HID hosts.
- When an emulator is detected, the app releases active touch input, sends a neutral touchscreen report, disables Touch HID input and enables Gamepad reports. When a regular app is active, it releases Gamepad state, disables Gamepad reports and re-enables Touch HID. The same Bluetooth HID pairing remains connected in both modes.
- The Viewer bottom button can manually switch between Touch HID and Gamepad when needed.
