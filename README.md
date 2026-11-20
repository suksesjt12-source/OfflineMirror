# OfflineMirror 1.36.0

OfflineMirror mirrors an Android display to another Android phone over the same private Wi-Fi or hotspot. Internet access is not required.

## Setup

1. Install OfflineMirror on both Android phones.
2. Connect both phones to the same Wi-Fi network or hotspot.
3. On the Host, tap **START SCREEN SHARING** and approve screen capture.
4. On the Host, tap **SHOW PAIRING QR**.
5. On the Viewer, scan the QR and connect to the Host.
6. Enable Bluetooth on both phones. In Viewer, tap **PAIR BT**.
7. Because the HID composite profile was corrected in v1.36.0, remove the old OfflineMirror pairing from the Host Bluetooth settings, then pair **OfflineMirror Gamepad + Mouse** again. Keep Viewer open.

## Bluetooth HID gamepad and mouse

- Gamepad and relative mouse are separate HID top-level collections under a composite Bluetooth HID profile.
- The SDP device class advertises the mouse subclass together with the gamepad subclass, instead of advertising only a gamepad. This is needed for hosts to recognize both input collections.
- Gamepad reports use Android-oriented button usage IDs, a hat-switch D-pad, X/Y and Z/Rz axes, and C5/C4 trigger axes.
- Mouse movement is accumulated before reports are sent. Fast trackpad movement is split into valid signed HID packets instead of replacing earlier movement samples.
- Mouse button press/release transitions are sent independently from movement so clicks are not lost.
- The Viewer trackpad retains fractional motion and tracks its mouse pointer independently from the left/right click pointers.
- Gamepad stick zones are captured before button zones; the D-pad and shoulder/start/select hit areas have been spaced to reduce accidental or ambiguous presses.
- A focus loss, gamepad overlay hide, or activity shutdown sends neutral controller state to prevent held buttons.

## Automatic mapping and compatibility

Android's controller usage table maps A/B/X/Y to Button usages 1/2/4/5, L1/R1 to usages 7/8, the D-pad to the hat switch, the left stick to X/Y, the right stick to Z/Rz, and the triggers to Simulation Controls C5/C4. Compatible games and emulators can receive these standard Android input events. Individual games can still use a custom mapping screen.

## Mouse controls

In Viewer, tap **MOUSE** to show the trackpad and cursor preview. Drag the left portion of the trackpad to move the Bluetooth mouse pointer on the Host. Tap the upper-right zone for left click and the lower-right zone for right click. The visible cursor preview follows trackpad movement on the Viewer; the paired Host should also show the system pointer when its Bluetooth HID stack supports mouse input.

## HUD

Use **HUD −** to hide the status area and toolbar (Pair, Settings, Mouse, volume, Media and Clipboard). **HUD +** restores the controls. The setting is remembered.

## Network channels

- Video: TCP port 48888 (H.264)
- Audio: TCP port 48889 (optional; Android 10+ and capture-compatible apps)
- Utility commands: TCP port 48890 (authenticated clipboard, volume, media and ping only)
- Wi-Fi does not carry touch injection, gamepad input or mouse input.

## Compatibility notes

- Screen mirroring: Android 5.0+
- Bluetooth HID gamepad and mouse: Android 9+ and vendor HID Device support
- Audio playback capture: Android 10+ and capture-compatible apps
- Host firmware must expose support for the HID Device profile and composite mouse/gamepad collections.
