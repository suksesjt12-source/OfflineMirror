# OfflineMirror 1.5.0

OfflineMirror mirrors one Android phone to another over the same local Wi-Fi network or hotspot. It does not need a cloud server or internet access after both phones join the same local network.

## Version 1.5.0

- Pairing keys and socket authentication have been removed.
- Connection now uses only the Host phone's local IPv4 address.
- Host and Viewer show their own local IP addresses separately.
- Video, optional playback audio, and remote touch remain supported.
- The Host prepares video, audio, and control listening ports before accepting connections.
- QR generation/scanning remains removed.

## Build the APK

Open the Actions tab, select **Build OfflineMirror APK**, and run **Run workflow**. After the workflow succeeds, download the **OfflineMirror-debug-apk** artifact. The APK is named `app-debug.apk`.

## Use on two phones

1. Install OfflineMirror on both phones.
2. Connect both phones to the same private Wi-Fi network, or enable a hotspot on one phone and connect the second phone to it.
3. On the Host, select video quality, frame rate, bitrate, and whether to share playback audio.
4. Tap **START SCREEN SHARING** and approve Android's screen-capture permission. Grant RECORD_AUDIO if requested for audio.
5. Read the Host's local IP address on the Host screen. Tap **COPY HOST ADDRESS** to copy the IP address only.
6. On the Viewer, enter the Host IP in **Host IP address**, enable audio reception if required, then tap **CONNECT TO HOST**.
7. The Viewer displays its own local IP and the Host IP separately.
8. For remote touch, the Host owner must tap **ENABLE REMOTE TOUCH ON THIS PHONE**, open Android Accessibility settings, and manually enable **OfflineMirror Remote Touch**.
9. To end the session, tap **STOP SHARING** on the Host or stop the foreground service from its notification.

## Address and network notes

- Each phone receives its own LAN address from the Wi-Fi router or hotspot. OfflineMirror detects and displays the address; it does not force or assign random IPs.
- Enter only the Host IP address, without the port number. The app uses its configured service ports automatically.
- Both devices must be on the same reachable local network. Some guest Wi-Fi networks isolate clients and prevent mirroring.

## Important security warning

**This version has no connection authentication.** Any device that can reach the Host's local IP and service ports may connect. The first connecting device can receive the screen stream and send touch gestures if the Host has enabled the Accessibility service. Use only a private network you control, do not expose the service ports to public networks, and disable Remote Touch in Android Settings when it is not needed.

The stream uses ordinary local TCP sockets, not TLS encryption. Notifications and private information visible on the Host may appear on the Viewer. Avoid mirroring sensitive content.

## Audio limitations

Android playback capture is available from Android 10 (API 29). Only playback from apps that permit capture can be received; some apps block capture. Protected media and some communication/system sounds may not be available.

## Current scope

- One Host and one Viewer per service session.
- Video, optional device playback audio, local IP-only connection, and basic touch gestures.
- No QR pairing, pairing key, microphone streaming, keyboard text injection, file transfer, internet relay, or multi-viewer support.

## Platform

- Android minSdk 26 (Android 8.0)
- compileSdk/targetSdk 35
- Java 17
