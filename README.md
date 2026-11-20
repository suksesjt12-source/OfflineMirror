# OfflineMirror 1.35.0

OfflineMirror streams an Android screen to another Android phone over the same private Wi-Fi or hotspot. Internet access is not required.

## Setup

1. Install OfflineMirror on both Android phones.
2. Connect both phones to the same Wi-Fi network or hotspot.
3. On the phone whose screen will be shared, tap **START SCREEN SHARING** and approve screen capture.
4. On the Host, tap **SHOW PAIRING QR**.
5. On the other phone, scan the QR and connect as Viewer.
6. Enable Bluetooth on both phones. In Viewer, tap **PAIR BT**. Leave Viewer open while pairing **OfflineMirror Gamepad + Mouse** from the Host Bluetooth settings.

## Bluetooth HID gamepad and mouse

- Virtual gamepad and mouse reports travel through Bluetooth HID. No touchscreen gesture or touch-injection path exists on the Host.
- The controller uses standard Android-oriented HID button order: A, B, X, Y, L1, R1, L2, R2, Select, Start, plus a four-way/diagonal D-pad hat and left/right analog axes.
- L2/R2 are mapped to the standard trigger button slots and mirrored to the Brake/Accelerator axes so games that read either representation can use them.
- The Viewer offers a dual-stick gamepad overlay. Portrait layout spacing is adjusted to reduce overlap between the right stick and face buttons.
- Button press and release edges are queued individually; only continuous axis/mouse movement is coalesced. A focus loss, overlay hide, or activity shutdown sends neutral input to reduce stuck buttons.
- Tap **MOUSE** in Viewer to show the Bluetooth trackpad and a visible cursor on the mirrored preview. Drag in the trackpad to move the pointer; use LEFT and RIGHT zones for clicks.
- Use **HUD −** to hide the status panel, pairing, settings, mouse, volume, media and clipboard toolbar. **HUD +** restores it. The setting is remembered.
- Android 9+ and a vendor Bluetooth stack exposing the HID Device profile are required. Keep Viewer open in the foreground while pairing and playing.

## Automatic mapping and compatibility

The descriptor follows common Android game-controller HID usage order so Android's input system can classify the device and provide standard gamepad button/axis events to compatible games and emulators. Individual games or emulators may still define custom layouts or ignore external controllers; a universal forced mapping cannot be guaranteed. Use the game's own controller mapping screen if its displayed layout differs.

## Network channels

- Video: TCP port 48888 (H.264)
- Audio: TCP port 48889 (optional; Android 10+ and capture-compatible apps)
- Utility commands: TCP port 48890 (authenticated clipboard, volume, media and ping only)
- The Wi-Fi utility channel does not carry gamepad, mouse, tap, hold, swipe or touch-injection input.

## Compatibility notes

- Screen sharing: Android 5.0+
- Bluetooth HID gamepad and mouse: Android 9+ and vendor HID Device support
- Audio playback capture: Android 10+ and only apps that permit capture
- Device firmware and individual games/emulators may restrict screen capture or controller support.
