# OfflineMirror 1.3.0

OfflineMirror mirrors a phone screen to another phone over the same local Wi-Fi network or hotspot. The application does not use a cloud server or require internet access after both devices are connected to the local network.

## New in version 1.3.0

- **QR pairing lebih andal:** payload QR berversi, divalidasi, membawa semua port layanan, dan memakai koreksi kesalahan QR.
- **Alamat IP setiap HP:** Host menampilkan IP lokalnya; Viewer menampilkan IP Viewer dan IP Host secara terpisah. Router/hotspot yang memberikan alamat IP unik pada tiap perangkat.
- **Koneksi lebih siap:** port video, audio, dan kontrol di-bind sebelum Viewer melakukan pemindaian QR. Opsi reuse diatur sebelum port di-bind.

- **QR pairing:** the Host creates a QR code containing the local IP address, service ports, audio setting, and a random session key. The Viewer scans it with the camera.
- **Remote touch:** tap, long-press, and swipe gestures on the Viewer can be sent to the Host. The Host owner must manually enable the OfflineMirror Remote Touch Accessibility service in Android Settings. The service does not retrieve window content; it only dispatches gestures received over the authenticated local session.
- **Audio playback streaming:** optional device playback audio is captured on Android 10+ with Android's AudioPlaybackCapture API and played through the Viewer. The Host must grant the RECORD_AUDIO permission.
- **Session authentication:** the video, audio, and touch sockets require the random key embedded in the Host's QR. A Viewer without the key is rejected.
- **Automatic portrait/landscape orientation** remains supported.
- 720p/1080p, 15/24/30 FPS, and 2/4/6 Mbps video settings remain available.

## Build the APK

Open the Actions tab in this repository, select **Build OfflineMirror APK**, and run **Run workflow**. After the workflow succeeds, download the **OfflineMirror-debug-apk** artifact from its run summary. The APK is named app-debug.apk.

## Use on two phones

1. Install the same OfflineMirror version on both phones.
2. Connect both phones to the same Wi-Fi network, or enable a hotspot on one phone and connect the second phone to it.
3. On the Host phone, select video quality and frame rate. Leave audio enabled only when you want to share playback audio.
4. Tap **START SCREEN SHARING** and approve Android's screen-capture permission. Grant RECORD_AUDIO if you want audio.
5. Show the Host pairing QR. On the Viewer phone, tap **SCAN HOST QR CODE** and scan it.
6. The Viewer opens the screen stream and attempts the audio and touch connections.
7. For remote touch, the Host owner must open **ENABLE REMOTE TOUCH ON THIS PHONE**, go to Android Accessibility settings, and manually enable **OfflineMirror Remote Touch**. This permission is not enabled by the app automatically.
8. Touch, swipe, or long-press within the Viewer area to send the gesture to the Host.
9. Stop sharing from the Host app or its persistent notification. Stopping the session invalidates the current pairing key.

## Important audio limitations

Android's playback capture API is available from Android 10 (API 29). Only playback from apps that permit capture can be received; apps may block capture for privacy or policy reasons. Protected media and some communication/system sounds may not be available. Audio streaming may not work on Android 8 or 9.

## Security and privacy

- The QR contains a random session key. Treat it like a temporary access code and show it only to the intended Viewer.
- The stream uses ordinary local TCP sockets, not TLS encryption. Use a trusted private hotspot or Wi-Fi network; do not expose these ports to public networks.
- The Host owner must approve screen capture and explicitly enable the Accessibility service before remote gestures can be injected.
- Screen notifications and visible private information may appear on the Viewer. Do not mirror sensitive content.
- Remote touch is limited to an active, explicitly paired OfflineMirror session. Disable the Accessibility service in Android Settings when you no longer need it.

## Current scope

- One Host and one Viewer.
- Video, optional system playback audio, QR pairing, and basic touch gestures.
- No microphone streaming, keyboard text injection, file transfer, internet relay, or multi-viewer support.
- Actual latency and supported audio depend on device hardware, Android version, local network quality, and the app being captured.

## Platform

- Android minSdk 26 (Android 8.0)
- compileSdk/targetSdk 35
- Java 17
