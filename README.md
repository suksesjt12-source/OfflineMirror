# OfflineMirror 1.52.0

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

The Viewer phone acts as one Bluetooth HID peripheral at a time. Select the desired mode from the **MODE** control:

- **Gamepad HID** registers a standard gamepad descriptor with 16 digital buttons and four 8-bit axes for the two analog sticks.
- **Touch HID** registers a separate digitizer/touchscreen descriptor and sends absolute screen coordinates.

These are independent Bluetooth HID registrations, not a composite device. Switching modes stops and unregisters the active HID application before registering the other descriptor. The Host may require the old OfflineMirror Bluetooth pairing to be removed and the newly advertised mode to be paired again. Keep Viewer open while pairing.

Gamepad input is sent on button edges immediately and analog updates are coalesced on a dedicated high-priority worker. If the previously paired Host drops the HID connection while the Viewer remains active, the Viewer makes a guarded reconnect attempt. The virtual stick has a configurable 0–20% deadzone, sensitivity, and Linear/Dynamic/Precision response curves. Button edges are sent without an artificial hold delay. Physical-controller feel still depends on the Host Bluetooth stack, report scheduling, and the game. L2/R2 are digital buttons in this build; analog trigger axes are not exposed.

Touch HID currently supports one absolute contact. Multi-touch is not advertised by this descriptor. Touchscreen HID support varies substantially by Android version and vendor firmware; some phones accept the pairing but do not route digitizer reports into ordinary touch input.

The HUD editor lets the Viewer reposition individual gamepad controls. Positions, sensitivity, deadzone, response curve and layout settings are stored locally. Focus loss and activity shutdown release active gamepad/touch states. No root or Accessibility service is used by the Bluetooth HID input path.

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
