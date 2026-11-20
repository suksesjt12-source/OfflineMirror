# OfflineMirror 1.34.0

OfflineMirror streams an Android screen to another Android phone over the same private Wi-Fi or hotspot. Internet access is not required.

## Setup

1. Install OfflineMirror on both Android phones.
2. Connect both phones to the same Wi-Fi network or hotspot.
3. On the phone whose screen will be shared, tap **START SCREEN SHARING** and approve screen capture.
4. On the Host, tap **SHOW PAIRING QR**.
5. On the other phone, scan the QR and connect as Viewer.
6. Enable Bluetooth on both phones. In Viewer, tap **PAIR BT**. If not already paired, leave Viewer open and pair **OfflineMirror Gamepad + Mouse** from the Host Bluetooth settings.

## Bluetooth HID gamepad + mouse (version 1.34.0)

- Gamepad and mouse reports are transmitted over Bluetooth HID. Wi-Fi does not carry gamepad, mouse, tap, hold, or swipe input.
- The composite HID descriptor exposes a standard Game Pad collection and a separate relative Mouse collection.
- Standard button usages, D-pad hat, left X/Y axes, right Z/Rz axes and analog L/R trigger usages improve Android game-controller recognition.
- Viewer includes a virtual gamepad with two independent sticks, D-pad, face buttons, shoulder buttons, Start/Select and triggers.
- Optional mouse pad: tap **MOUSE** in Viewer. Drag in the pad area to move the Bluetooth pointer; use LEFT and RIGHT zones for clicks. It remains available alongside the virtual gamepad.
- Android 9+ and a vendor Bluetooth stack exposing the HID Device profile are required. Keep Viewer open in the foreground while pairing and playing.

## Automatic mapping and compatibility

Android-compatible HID usage IDs and axis layout are used so supported games and emulators can detect the controller through Android's system input stack. Individual games and emulators may still provide their own mapping screen or ignore a controller profile; a universal forced mapping cannot be guaranteed.

## Network channels

- Video: TCP port 48888 (H.264)
- Audio: TCP port 48889 (optional; Android 10+ and capture-compatible apps)
- Utility commands: TCP port 48890 (authenticated clipboard, volume, media and ping only)
- Input is Bluetooth HID only. The Wi-Fi utility channel rejects legacy touch and gamepad packets.

## Compatibility notes

- Screen sharing: Android 5.0+
- Bluetooth HID gamepad + mouse: Android 9+ and vendor HID Device support
- Audio playback capture: Android 10+ and only apps that permit capture
- Device firmware and individual games/emulators may restrict screen capture or controller support.
