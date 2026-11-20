# OfflineMirror

OfflineMirror is an Android screen-mirroring prototype for two phones on the same local Wi-Fi network or phone hotspot. The video stream uses H.264 over a direct TCP connection. Internet access, cloud accounts, and remote servers are not required.

## Current features

- Host mode: captures this phone's screen after the user approves Android's MediaProjection prompt.
- Viewer mode: connects to the host IP address and renders the H.264 stream.
- Local TCP streaming on port 48888.
- Selectable 720p/1080p maximum dimension, 15/24/30 FPS, and 2/4/6 Mbps target bitrate.
- Foreground notification with a Stop action.
- GitHub Actions builds a debug APK on pushes to main and on manual workflow dispatch.

## Build the APK

Open the Actions tab in this repository, select Build OfflineMirror APK, and run Run workflow. After the run completes, open its summary and download the OfflineMirror-debug-apk artifact. The APK is named app-debug.apk.

## How to connect two phones

1. Connect both phones to the same Wi-Fi network, or turn on the hotspot on one phone and connect the other phone to it.
2. Open OfflineMirror on the phone whose screen will be shared.
3. Select the quality, frame rate, and bitrate, then tap START SCREEN SHARING.
4. Approve Android's screen-capture permission. Keep OfflineMirror running; Android displays an ongoing notification while sharing.
5. Read the host IP address shown in the app. If the displayed address is unavailable or incorrect, check the phone's Wi-Fi details and use its local IPv4 address.
6. On the second phone, open OfflineMirror, enter the host IP (without the port), and tap CONNECT TO HOST.
7. Stop sharing from the host app or its persistent notification.

## Important limitations

- This is an initial streaming prototype. It currently supports one viewer at a time.
- Both phones must be on the same local network and must be able to reach each other. Some guest Wi-Fi networks enable client isolation and block this.
- Android requires explicit screen-capture approval each time a new host session starts.
- The Viewer displays the shared screen; remote touch/control, audio streaming, QR pairing, automatic discovery, and encrypted transport are not implemented in this first version.
- Actual resolution, frame rate, and latency depend on the phone's H.264 encoder, Wi-Fi conditions, and Android version.
- Only share a screen when you have permission to display its contents. Notifications and private information visible on the host screen may also be transmitted.

## Platform

- Android minSdk 26 (Android 8.0)
- compileSdk/targetSdk 35
- Java 17
