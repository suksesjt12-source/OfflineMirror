# OfflineMirror 1.1.0

OfflineMirror is an Android screen-mirroring prototype for two phones connected to the same local Wi-Fi network or phone hotspot. It sends H.264 video directly over a local TCP connection. Internet access, cloud accounts, and remote servers are not needed.

## Version 1.1.0

- Automatic portrait/landscape orientation in both Host and Viewer.
- Host detects display rotation, resizes its existing Android VirtualDisplay, recreates the H.264 encoder, and sends a stream reconfiguration header.
- Viewer rebuilds its decoder when the Host changes orientation.
- Full-screen Viewer hides system navigation/status bars; system UI can be revealed with the normal edge gesture.
- Host quality controls: 720p/1080p maximum dimension, 15/24/30 FPS, and 2/4/6 Mbps target bitrate.
- Foreground notification with a Stop action.
- GitHub Actions builds a debug APK on pushes to main and on manual workflow dispatch.

## Build the APK

Open the Actions tab in this repository, select Build OfflineMirror APK, and run Run workflow. After the run completes, open its summary and download the OfflineMirror-debug-apk artifact. The APK is named app-debug.apk.

## How to connect two phones

1. Connect both phones to the same Wi-Fi network, or turn on the hotspot on one phone and connect the other phone to it.
2. Install OfflineMirror 1.1.0 on both phones.
3. Open OfflineMirror on the phone whose screen will be shared.
4. Select the quality, frame rate, and bitrate, then tap START SCREEN SHARING.
5. Approve Android's screen-capture permission. Keep OfflineMirror running; Android displays an ongoing notification while sharing.
6. Read the host IP address shown in the app. If the displayed address is unavailable or incorrect, check the phone's Wi-Fi details and use its local IPv4 address.
7. On the second phone, open OfflineMirror, enter the host IP (without the port), and tap CONNECT TO HOST.
8. Rotate either phone between portrait and landscape. The Viewer window follows the Viewer phone's orientation, while the Host stream updates its encoded dimensions when the Host phone rotates.
9. Stop sharing from the Host app or its persistent notification.

## Limitations

- This is an early prototype and supports one Viewer at a time.
- Both phones must be on the same local network and must be able to reach each other. Some guest Wi-Fi networks enable client isolation and block this.
- Android requires explicit screen-capture approval each time a new Host session starts.
- The Viewer displays the shared screen. Remote touch/control, audio streaming, QR pairing, automatic device discovery, and encrypted transport are not implemented.
- Actual resolution, frame rate, and latency depend on the phone's H.264 encoder, Wi-Fi conditions, and Android version.
- The rotation transition briefly restarts the video encoder; a short pause during the transition is expected.
- Only share a screen when you have permission to display its contents. Notifications and private information visible on the Host screen may also be transmitted.

## Platform

- Android minSdk 26 (Android 8.0)
- compileSdk/targetSdk 35
- Java 17
