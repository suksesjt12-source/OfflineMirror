# OfflineMirror 1.4.0

OfflineMirror mirrors one Android phone to another over the same local Wi-Fi network or hotspot. It does not need a cloud server or internet access after both phones join the same local network.

## Version 1.4.0

- QR generation, QR scanning, camera-based pairing, and ZXing dependencies have been removed.
- Manual pairing uses the Host's local IPv4 address and a random session key.
- Host and Viewer show their own local IP addresses separately.
- Video, optional playback audio, and remote touch remain supported.
- Host prepares its video, audio, and control listening ports before accepting connections.

## Build the APK

Open the Actions tab, select **Build OfflineMirror APK**, and run **Run workflow**. After the workflow succeeds, download the **OfflineMirror-debug-apk** artifact. The APK is named `app-debug.apk`.

## Use on two phones without QR

1. Install OfflineMirror on both phones.
2. Connect both phones to the same Wi-Fi network, or enable a hotspot on one phone and connect the second phone to it.
3. On the Host, select video quality, frame rate, bitrate, and whether to share playback audio.
4. Tap **START SCREEN SHARING** and approve Android's screen-capture permission. Grant RECORD_AUDIO if requested for audio.
5. On the Host screen, note the **Host IP address** and tap **COPY PAIRING KEY**.
6. On the Viewer, enter the Host IP in **Host IP address** and paste the copied key into **Pairing key from Host**. Enable audio reception if required, then tap **CONNECT TO HOST**.
7. The Viewer displays its own local IP and the Host IP separately.
8. For remote touch, the Host owner must tap **ENABLE REMOTE TOUCH ON THIS PHONE**, open Android Accessibility settings, and manually enable **OfflineMirror Remote Touch**.
9. To end the session, tap **STOP SHARING** on the Host or stop the foreground service from its notification. The displayed session key is invalidated when sharing is stopped.

## Address and pairing notes

- Each phone receives its own LAN address from the Wi-Fi router or hotspot. OfflineMirror detects and displays the address; it does not force or manually assign an IP.
- Enter only the Host IP address, without the port number. The app uses its configured service ports automatically.
- The session key is temporary. Share it only with the intended Viewer and do not post it publicly.
- Both devices must be on the same reachable local network. Some guest Wi-Fi networks isolate connected clients and prevent mirroring.

## Audio limitations

Android playback capture is available from Android 10 (API 29). Only playback from apps that permit capture can be received; some apps block capture. Protected media and some communication/system sounds may not be available.

## Security and privacy

- The video, audio, and control services require the random session key.
- The stream uses ordinary local TCP sockets, not TLS encryption. Use a trusted private Wi-Fi network or hotspot; do not expose the ports to public networks.
- The Host owner must approve screen capture and explicitly enable the Accessibility service before remote gestures can be injected.
- Notifications and private information visible on the Host may appear on the Viewer. Avoid mirroring sensitive content.

## Current scope

- One Host and one Viewer.
- Video, optional device playback audio, manual IP/key pairing, and basic touch gestures.
- No QR pairing, microphone streaming, keyboard text injection, file transfer, internet relay, or multi-viewer support.

## Platform

- Android minSdk 26 (Android 8.0)
- compileSdk/targetSdk 35
- Java 17
