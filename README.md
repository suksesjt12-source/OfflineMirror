# OfflineMirror 1.40.1

OfflineMirror mirrors an Android display to another Android phone over the same private Wi-Fi or hotspot. Internet access is not required.

## Setup

1. Install OfflineMirror on both Android phones.
2. Connect both phones to the same Wi-Fi network or hotspot.
3. On the Host, tap **START SCREEN SHARING** and approve screen capture.
4. On the Host, tap **SHOW PAIRING QR**.
5. On the Viewer, scan the QR and connect to the Host.
6. Enable Bluetooth on both phones. In Viewer, tap **PAIR BT**.
7. Remove the old **OfflineMirror Gamepad + Mouse** pairing from Host Bluetooth settings, then pair the new **OfflineMirror Gamepad** device. Keep Viewer open.

## Bluetooth HID gamepad and Direct Touch

- Bluetooth HID advertises only the virtual gamepad collection. No mouse collection or cursor reports are registered.
- The SDP device advertises a gamepad-only class and the standard Android button, hat-switch, stick and trigger usages.
- Gamepad reports use Android-oriented button usage IDs including L3/R3, a hat-switch D-pad, X/Y and Z/Rz axes, and C5/C4 trigger axes.
- Direct Touch forwards taps, holds, swipes and up to five simultaneous pointer paths over the authenticated Wi-Fi channel. No mouse pointer or scroll-wheel emulation is used.
- Android Accessibility Gesture Dispatch applies the completed gesture on the Host; system protection or newer gestures can cancel it.
- Home and Back buttons use Android Accessibility global actions and can be hidden with NAV − / NAV +.
- LAYOUT moves each gamepad button, D-pad direction and analog stick independently. Each position is saved locally.
- L3 and R3 are the clickable centers of the virtual analog sticks and use additional HID button usages 13/14.
- D-pad hit targets are spaced apart so adjacent directions cannot steal each other's touch.
- Gamepad button pointers are reconciled against active MotionEvent pointers. A final pointer-up/cancel always sends a neutral state, and queued axis reports are serialized with D-pad/button edges so stale reports cannot reassert a released direction.
- Viewer decoder output buffers are drained while waiting for input buffers. Video sockets have a stall timeout and automatically reconnect; the decoder is released per connection but the Surface is retained across network retries.
- A focus loss, gamepad overlay hide, or activity shutdown sends neutral controller state to prevent held buttons.

## Automatic mapping and compatibility

Android's controller usage table maps A/B/X/Y to Button usages 1/2/4/5, L1/R1 to usages 7/8, the D-pad to the hat switch, the left stick to X/Y, the right stick to Z/Rz, and the triggers to Simulation Controls C5/C4. Compatible games and emulators can receive these standard Android input events. Individual games can still use a custom mapping screen.

## Direct Touch and navigation

Enable **OfflineMirror Remote Touch** in Android Accessibility settings on the Host phone before connecting. The service has gesture-injection permission only and does not request screen-content access. Touch the mirrored image outside the gamepad buttons. Direct Touch uses Android Accessibility gesture dispatch and requires Android 7.0+ on the Host. It does not require root. Some protected system screens may reject injected gestures.

## HUD

Use LAYOUT to enter edit mode, then drag each gamepad button, D-pad direction, or analog stick independently. Tap SELESAI when finished. Each position is saved automatically. Use NAV − / NAV + to hide or show Home and Back. Use HUD − to hide the status area and toolbar (Pair, Settings, Navigation, Layout, volume, Media and Clipboard). HUD + restores the controls. The setting is remembered.

## Network channels

- Video: TCP port 48888 (H.264)
- Audio: TCP port 48889 (optional; Android 10+ and capture-compatible apps)
- Utility commands: TCP port 48890 (authenticated clipboard, volume, media and ping only)
- Wi-Fi carries authenticated Direct Touch gestures and Home/Back commands; Bluetooth HID carries gamepad input.

## Compatibility notes

- Screen mirroring: Android 5.0+
- Bluetooth HID gamepad: Android 9+ and vendor HID Device support
- Direct Touch: Android 7.0+ with the Host Accessibility service enabled
- Audio playback capture: Android 10+ and capture-compatible apps
- Host firmware must expose support for the Bluetooth HID Device gamepad profile.
