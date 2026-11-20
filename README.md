# OfflineMirror 1.37.0

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
- The SDP device class advertises the combo mouse profile together with the gamepad subclass, instead of advertising only a gamepad. This is needed for hosts to recognize both input collections.
- Gamepad reports use Android-oriented button usage IDs, a hat-switch D-pad, X/Y and Z/Rz axes, and C5/C4 trigger axes.
- Mouse movement is accumulated before reports are sent. Fast trackpad movement is split into valid signed HID packets instead of replacing earlier movement samples.
- Mouse button press/release transitions are sent independently from movement so clicks are not lost.
- The Viewer now uses one pointer only on the Host; no duplicate software cursor is drawn over the mirrored image.
- The trackpad supports double-tap left-click, dedicated left/right click zones, and dedicated scroll-up/scroll-down controls.
- The mouse panel and complete gamepad overlay can be repositioned with the LAYOUT tool. Their normalized positions are saved locally and restored after reconnect/orientation changes.
- Gamepad button pointers are reconciled against active MotionEvent pointers. A final pointer-up/cancel always sends a neutral state, and queued axis reports are serialized with D-pad/button edges so stale reports cannot reassert a released direction.
- Viewer decoder output buffers are drained while waiting for input buffers. Video sockets have a stall timeout and automatically reconnect; the decoder is released per connection but the Surface is retained across network retries.
- A focus loss, gamepad overlay hide, or activity shutdown sends neutral controller state to prevent held buttons.

## Automatic mapping and compatibility

Android's controller usage table maps A/B/X/Y to Button usages 1/2/4/5, L1/R1 to usages 7/8, the D-pad to the hat switch, the left stick to X/Y, the right stick to Z/Rz, and the triggers to Simulation Controls C5/C4. Compatible games and emulators can receive these standard Android input events. Individual games can still use a custom mapping screen.

## Mouse controls

In Viewer, tap **MOUSE** to show the trackpad. Drag the left region to move the mouse pointer on the Host; double-tap the trackpad for left click. The right-side controls provide left click, right click, scroll up, and scroll down. The operating-system pointer is displayed by the Host only.

## HUD

Use LAYOUT to enter edit mode, then drag the mouse panel or the gamepad overlay to reposition it. Tap SELESAI when finished. Positions are saved automatically. Use HUD − to hide the status area and toolbar (Pair, Settings, Mouse, Layout, volume, Media and Clipboard). HUD + restores the controls. The setting is remembered.

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
