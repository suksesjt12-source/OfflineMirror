# OfflineMirror 1.58.0

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
- Remote utility commands use TCP port 48890 for authenticated clipboard, volume, media, ping and foreground-app profile events.
- The Host video server supports up to four Viewer connections. Audio and utility control are separate single-client channels.
- Resolution, frame-rate cap, bitrate and adaptive FPS can be selected on the Host. Actual performance depends on the encoder, Wi-Fi quality, thermal state and Android background restrictions.
- Video reconnects independently from the utility channel. Audio capture and playback retry after temporary disconnects.

## Bluetooth HID input

The Viewer phone includes a **Virtual Bluetooth Adapter Manager** with two independently switchable logical endpoints: Virtual Gamepad and Virtual Touch. Enabling both registers one Bluetooth HID composite peripheral with separate Gamepad and Digitizer top-level collections and Report IDs. Enabling only one selects its standalone HID descriptor. Both endpoints share one physical Android Bluetooth adapter and one HID connection; the app-level virtual profiles are not two system Bluetooth adapters or two independently pairable devices. The Host may expose both collections as one composite InputDevice or as multiple InputDevices, depending on its Android input stack. The Viewer can also switch the active overlay automatically when the Host foreground app changes, if a per-app profile has been saved. The Virtual Bluetooth Adapter Manager keeps the enabled logical endpoints selected; an app profile does not silently disable either endpoint:

- **Virtual Gamepad** routes 16 digital buttons, a standard 4-way/8-way hat switch for D-pad, and four 8-bit axes for the two analog sticks. D-pad directions are represented by the hat switch only to avoid duplicate directional button signals.
- **Virtual Touch** routes single-contact absolute digitizer coordinates. It can be enabled or disabled independently from Virtual Gamepad.

Gamepad-only and Touch-only are independent registrations. Dual HID Composite is one Bluetooth HID peripheral containing separate Gamepad and Digitizer top-level collections with separate Report IDs. Android may expose both collections as one InputDevice with two sources or as multiple InputDevices; behavior depends on the Host input stack. Switching between Gamepad, Touch and Composite stops and unregisters the active HID application before registering the selected descriptor. The Bluetooth bond normally remains stored, but the HID connection can briefly disconnect and reconnect while the profile changes.

Gamepad input is sent on button edges immediately and analog updates are coalesced on a dedicated high-priority worker. If the previously paired Host drops the HID connection while the Viewer remains active, the Viewer makes a guarded reconnect attempt. The virtual stick has a configurable 0–20% deadzone, sensitivity, and Linear/Dynamic/Precision response curves. Button edges are sent without an artificial hold delay. Physical-controller feel still depends on the Host Bluetooth stack, report scheduling, and the game. L2/R2 are digital buttons in this build; analog trigger axes are not exposed.

Touch HID currently supports one absolute contact. Multi-touch is not advertised by this descriptor. Touchscreen HID support varies substantially by Android version and vendor firmware; some phones accept the pairing but do not route digitizer reports into ordinary touch input. In Host mode, **SCAN INPUT BLUETOOTH** reads Android's external InputDevice registry and reports whether external Gamepad/Joystick and Touchscreen sources are visible. It excludes the Host's built-in touchscreen to avoid a false positive. This check does not inject input and does not request Accessibility.

**Virtual Bluetooth Adapter Manager:** open Viewer → BLUETOOTH and independently enable Virtual Gamepad and Virtual Touch. When both are enabled, one composite HID registration carries both report IDs. The manager reports whether each logical endpoint is enabled and counts reports routed by the app; these counters do not prove Android Host accepted the corresponding input sources. Run SCAN INPUT BLUETOOTH on the Host to inspect the external Gamepad/Joystick and Touchscreen sources.

**Smart App Profiles:** enable Usage Access for OfflineMirror on the Host phone when starting Host mode. The Host sends the foreground app package name over the authenticated local control channel; it does not send screen contents. In Viewer → control settings, save Gamepad HID or Touch HID for the currently active Host app. When that app becomes foreground again, Viewer switches the overlay and HID registration. Apps without a saved profile leave the current mode unchanged. Usage Access must be granted manually in Android Settings; first-time Bluetooth pairing still requires Android's normal user confirmation. Vendor task managers and usage-access restrictions may delay or suppress app-change events.

The HUD editor lets the Viewer reposition individual gamepad controls. Positions, sensitivity, deadzone, response curve, virtual endpoint enablement and layout settings are stored locally. Focus loss and activity shutdown release active gamepad/touch states. No root or Accessibility service is used by the Bluetooth HID input path. The Viewer status panel reports app-side routing counts; only the Host input scan can confirm which sources Android actually exposes.

## Compatibility and limitations

- Screen mirroring: Android 5.0+ (API 21+).
- Bluetooth HID Device mode: Android 9.0+ (API 28+) and vendor firmware that exposes HID Device support.
- Playback audio capture: Android 10+ (API 29+) and capture-compatible apps.
- Touch HID is experimental and is not guaranteed to work on every Host phone. Composite registration does not force an unsupported Host firmware to route digitizer input.
- The Host input diagnostic only reports what Android exposes; seeing a Touchscreen source is a compatibility signal, not a guarantee that every app/game accepts the input.
- Wi-Fi video and Bluetooth input are independent transports. Wi-Fi carries the stream, utilities and foreground-app events; Bluetooth uses one physical HID transport carrying either the Gamepad endpoint, Touch endpoint, or both as a composite descriptor.

## Troubleshooting

- **Bluetooth pairs but input does nothing**: pairing alone does not confirm Android routed HID input. Keep the Viewer open and connected, open OfflineMirror on Host, and tap **SCAN INPUT BLUETOOTH**. The report distinguishes external Gamepad/Joystick and Touchscreen sources. If a source is missing, reconnect the selected HID profile and scan again; if it remains absent, the Host's Android/vendor input stack may not support that HID collection. OfflineMirror cannot force unsupported firmware to route input.

- **QR scanner does not open**: grant camera permission and close other apps currently using the camera.
- **Viewer cannot connect**: confirm both phones are on the same Wi-Fi/hotspot, use the Host's current IP, and create a new QR after restarting sharing.
- **Black or frozen video**: keep the Host screen awake during initial testing, lower resolution/bitrate, and check whether Android has stopped the capture service.
- **No audio**: confirm Android 10+, playback-capture permission/context, and that the source app allows capture.
- **Bluetooth does not appear**: enable Bluetooth on both phones, tap PAIR BT on Viewer, approve discoverability when requested, and pair from Host settings. Some vendors do not support Bluetooth HID Device mode.
