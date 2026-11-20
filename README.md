# OfflineMirror 1.6.0

OfflineMirror mirrors one Android phone to another over the same local Wi-Fi network or hotspot. The Viewer connects using the Host's local IPv4 address only.

## Version 1.6.0 — black-screen and decoder startup fixes

- Start the H.264 encoder before attaching its input Surface to VirtualDisplay.
- Request an initial H.264 sync/key frame after encoder start and after screen rotation.
- Validate H.264 codec configuration before sending it to the Viewer.
- Improve Viewer startup and first-frame status messages.
- Validate that the Viewer rendering Surface is ready before starting the decoder.
- Preserve IP-only connection, audio streaming, and remote touch.

## Use on two phones

1. Install OfflineMirror on both phones.
2. Connect both phones to the same private Wi-Fi or hotspot.
3. On the Host, tap **START SCREEN SHARING** and approve Android's screen-capture permission.
4. Read the Host IP address and tap **COPY HOST ADDRESS**.
5. On the Viewer, enter the Host IP and tap **CONNECT TO HOST**.
6. Wait for the Viewer status to change to **Mirroring aktif**.
7. For remote touch, the Host owner must manually enable OfflineMirror Remote Touch in Android Accessibility settings.

## Network and security

This version has no QR pairing and no connection authentication. Any device that can reach the Host's local IP and service ports may connect. Use a private network you control. Remote Touch can send gestures to the Host when its Accessibility service is enabled. The TCP stream is not encrypted with TLS.

The router or hotspot assigns local addresses; OfflineMirror detects them and does not invent random IP addresses.

## Audio limitations

System playback capture requires Android 10+ and only works for apps that permit capture.

## Platform

- Android minSdk 26
- compileSdk/targetSdk 35
- Java 17
