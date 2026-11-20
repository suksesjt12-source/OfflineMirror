# OfflineMirror 1.31.0

OfflineMirror streams an Android screen to another Android phone over the same private Wi-Fi or hotspot. Internet access and Bluetooth are not required.

## Setup

1. Install OfflineMirror on both Android phones.
2. Connect both phones to the same Wi-Fi network or hotspot.
3. On the phone whose screen will be shared, tap **START SCREEN SHARING** and approve screen capture.
4. On the Host, tap **SHOW PAIRING QR**.
5. On the other phone, scan the QR and connect as Viewer.
6. For remote touch and Wi-Fi gamepad, enable **OfflineMirror Remote Control** in Android Accessibility settings on the Host.

## Wi-Fi gamepad (version 1.31.0)

- The Viewer sends analog and button state through the existing local TCP control channel, authenticated with the Host's session pairing key.
- No Bluetooth pairing, Bluetooth permissions, extra port, or internet connection is required.
- The Host Accessibility service translates the virtual gamepad to touch gestures at the default on-screen control positions.
- This is touch emulation, not a system-wide HID gamepad. Games that require a physical controller, block Accessibility gestures, or use a different touch layout may not respond as expected.
- The default mapping follows the virtual overlay: left analog near the lower-left, action buttons on the right, and shoulder buttons near the upper edge.

## Network channels

- Video: TCP port 48888 (H.264)
- Audio: TCP port 48889 (optional; Android 10+ and capture-compatible apps)
- Remote touch and gamepad: TCP port 48890
- QR pairing includes the Host's local IP, video port, and session pairing key. The control socket validates the same key.
- These channels are intended for a trusted local network. Pair only with devices you trust.

## Compatibility notes

- Screen sharing: Android 5.0+
- Remote touch and Wi-Fi gamepad gestures: Android 7.0+
- Persistent analog touch continuation: Android 8.0+
- Audio playback capture: Android 10+ and only apps that permit capture
- Android vendors and individual games can restrict screen capture or Accessibility gestures.

Screen-Off Mirroring uses a partial CPU wake lock while the Host display is off, but Android may still stop MediaProjection on some devices. The feature does not guarantee that every device keeps capturing after its screen is turned off.
