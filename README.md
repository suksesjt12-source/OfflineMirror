# OfflineMirror 1.59.0 — Dual Control Builds

OfflineMirror mirrors an Android screen to another Android phone over a shared private Wi-Fi network or hotspot. No internet connection is required.

## Two APK variants

### Gamepad + Touch Direct Wi-Fi
- Bluetooth HID exposes only a standard gamepad (D-pad, buttons and dual analog sticks).
- Touch events use the authenticated Wi-Fi control channel.
- On Host, enable **OfflineMirror Remote Touch** from Android Accessibility settings using **ENABLE TOUCH DIRECT (ACCESSIBILITY)**.
- The Host service dispatches gesture coordinates supplied by the paired Viewer. It does not retrieve window or screen content.
- Direct touch works while Host sharing and the authenticated Wi-Fi control channel are active.

### Touch HID Multitouch
- Bluetooth HID exposes only a Digitizer Touchscreen profile. No gamepad endpoint or gamepad overlay is included.
- Descriptor and input reports carry up to five simultaneous contacts.
- No Accessibility permission is required.
- Android Host/vendor support for external HID touchscreen devices varies. Pairing does not guarantee Android routes digitizer reports into apps.

The variants use different application IDs and can be installed side by side.

## Pairing

1. Install the same variant on both Android phones.
2. Connect both phones to the same Wi-Fi/hotspot.
3. On Host, tap **START SCREEN SHARING** and approve the system screen-capture prompt.
4. On Gamepad + Touch Direct Wi-Fi, enable **OfflineMirror Remote Touch** in Host Accessibility settings.
5. Show the Host pairing QR and scan it on Viewer.
6. Pair Bluetooth through Android Settings, then tap **PAIR GAMEPAD** or **PAIR TOUCH HID** in Viewer.
7. Keep both phones on the same local network. Restarting Host sharing creates a new session key and QR.

The QR contains a private-network address, video port and a random per-session key. Stop sharing to invalidate it. Do not share pairing QR codes or keys with untrusted people.

## Network and compatibility

- Video: H.264 over TCP port 48888.
- Optional playback audio: TCP port 48889; Android 10+ and source-app capture support are required.
- Authenticated utilities and Touch Direct: TCP port 48890.
- Video supports up to four Viewer connections; the control connection is authenticated separately.
- Touch Direct uses Android Accessibility gesture dispatch. It can be blocked on secure system surfaces, lock screens, protected apps or vendor-restricted contexts. Host owner approval is required.
- Touch HID Multitouch is experimental. Five-contact HID descriptor/report support is implemented, but the Host may not expose it as an external touchscreen.
- Bluetooth HID Device mode needs Android 9+ and vendor firmware support.

## GitHub Actions release builds

- `gamepadWifiRelease` → `OfflineMirror-Gamepad-WiFi-1.59.0-release.apk`
- `touchHidRelease` → `OfflineMirror-Touch-HID-Multitouch-1.59.0-release.apk`

These sideloadable CI release builds use the generated CI debug certificate. They are not production Play Store-signed packages. A persistent private release keystore is required for future in-place updates with the same signing identity.
