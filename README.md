# OfflineMirror 1.50.0

OfflineMirror streams an Android screen to another Android phone over a shared private Wi-Fi network or hotspot. Internet access is not required.

## Pairing and first connection

1. Install OfflineMirror on both Android phones.
2. Connect both phones to the same Wi-Fi network or hotspot.
3. On the Host phone, tap **START SCREEN SHARING** and approve Android's screen-capture prompt.
4. On the Host phone, tap **SHOW PAIRING QR**.
5. On the Viewer phone, tap **SCAN HOST QR**. The app fills in the Host IP and session key, then starts the connection.
6. Keep both phones on the same local network. If QR scanning is unavailable, enter the Host's local IPv4 address and session key manually.

The QR code contains the Host's private-network address, video port and a random per-session key. Stop sharing to invalidate the previous session key. Do not share the QR code or key with untrusted people.

## Streaming

- Video uses H.264 over TCP port 48888.
- Optional playback audio uses TCP port 48889. It requires Android 10+ and apps that permit playback capture.
- Remote utility commands use TCP port 48890 for authenticated clipboard, volume, media and ping messages.
- The Host video server supports up to four Viewer connections. Audio and utility control are separate single-client channels.
- Resolution, frame-rate cap, bitrate and adaptive FPS can be selected on the Host. Actual performance depends on the encoder, Wi-Fi quality, thermal state and Android background restrictions.
- Video reconnects independently from the utility channel. Audio capture and playback retry after temporary disconnects.

## Bluetooth HID input

The Viewer phone acts as the Bluetooth HID peripheral. Tap **PAIR BT** in Viewer, then pair the advertised OfflineMirror device from the Host phone's Android Bluetooth settings. Keep Viewer open while using Bluetooth input.

Bluetooth registers one **composite HID profile** containing two independent top-level collections with separate Report IDs:

- **Gamepad (Report ID 1)**: 16 digital button usages and four 8-bit axes (X, Y, Z and Rz) for two analog sticks. D-pad directions are digital buttons.
- **Touchscreen (Report ID 2)**: experimental absolute-coordinate touchscreen input with one contact.

Both report channels remain enabled simultaneously; the Viewer can show the gamepad overlay while touch gestures on unoccupied screen areas continue to be sent through the touchscreen collection. The gamepad overlay can be hidden without unregistering either HID collection. Some Android Bluetooth HID Host implementations or vendor firmware may ignore/reject the touchscreen collection. If moving from an older build, remove the old OfflineMirror Bluetooth pairing on the Host and pair the updated composite device again.

This build does not expose separate Consumer Control reports for Home, Back or Recent Apps, and does not expose analog trigger axes. Controller compatibility still depends on the Android Host and the target game.

The HUD editor lets the Viewer reposition individual gamepad controls. Positions, sensitivity and layout settings are stored locally. Focus loss and activity shutdown release active gamepad/touch states.

No root or Accessibility service is used by the Bluetooth HID input path.

## Compatibility and limitations

- Screen mirroring: Android 5.0+ (API 21+).
- Bluetooth HID Device mode: Android 9.0+ (API 28+) and vendor firmware that exposes HID Device support.
- Playback audio capture: Android 10+ (API 29+) and capture-compatible apps.
- Touch HID is experimental and is not guaranteed to work on every Host phone.
- Wi-Fi video and Bluetooth input are independent transports. Wi-Fi carries the stream and utility channel; Bluetooth carries the composite Touch + Gamepad HID profile.

## Troubleshooting

- **QR scanner does not open**: grant camera permission and close other apps currently using the camera.
- **Viewer cannot connect**: confirm both phones are on the same Wi-Fi/hotspot, use the Host's current IP, and create a new QR after restarting sharing.
- **Black or frozen video**: keep the Host screen awake during initial testing, lower resolution/bitrate, and check whether Android has stopped the capture service.
- **No audio**: confirm Android 10+, playback-capture permission/context, and that the source app allows capture.
- **Bluetooth does not appear**: enable Bluetooth on both phones, tap PAIR BT on Viewer, approve discoverability when requested, and pair from Host settings. Some vendors do not support Bluetooth HID Device mode.
