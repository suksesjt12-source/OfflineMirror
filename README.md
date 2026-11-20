# OfflineMirror 1.6.0

OfflineMirror mirrors one Android phone to another over the same local Wi-Fi network or hotspot. The Viewer connects using the Host's local IPv4 address only.

## Version 1.6.0 — black-screen and decoder startup fixes

- Start the H.264 encoder before attaching its input Surface to VirtualDisplay.
- Request an initial H.264 sync/key frame after encoder start and after screen rotation.
- Validate H.264 codec configuration before sending it to the Viewer.
- Improve Viewer startup and first-frame status messages.
- Validate that the Viewer rendering Surface is ready before starting the decoder.

## Use on two phones

1. Install OfflineMirror on both phones.
2. Connect both phones to the same private Wi-Fi or hotspot.
3. On the Host, tap **START SCREEN SHARING** and approve Android's screen-capture permission.
4. Read the Host IP address and tap **COPY HOST ADDRESS**.
5. On the Viewer, enter the Host IP and tap **CONNECT TO HOST**.
6. Wait for the Viewer status to change to **Mirroring aktif**.

## Network and security


The router or hotspot assigns local addresses; OfflineMirror detects them and does not invent random IP addresses.

## Audio limitations

System playback capture requires Android 10+ and only works for apps that permit capture.

## Platform

- Android minSdk 26
- compileSdk/targetSdk 35
- Java 17


## Two-phone hotspot setup
OfflineMirror uses only the local network. Enable the hotspot on either phone and connect the second phone to it. The phone acting as the hotspot does not have to be the Host. On the phone whose screen will be shared, start Host mode and copy its displayed local IP address; on the other phone, enter that IP and the current pairing key in Viewer mode. Both phones must be connected to the same hotspot network. Internet access is not required.

This release does not include remote touch or an Accessibility Service. Video uses H.264 over the local network; optional audio uses a separate local TCP connection.
