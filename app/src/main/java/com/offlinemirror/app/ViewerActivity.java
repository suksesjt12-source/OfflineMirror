package com.offlinemirror.app;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.SurfaceTexture;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.media.MediaCodec;
import android.media.MediaFormat;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

public class ViewerActivity extends Activity implements TextureView.SurfaceTextureListener {
    private static final int VIDEO_PORT = 48888;
    private static final int AUDIO_PORT = 48889;
    private static final int CONTROL_PORT = 48890;
    private static final int MAGIC = 0x4F4D4952;
    private static final int CONTROL_MAGIC = 0x4F4D4354;
    private static final int REQUEST_BT_PERMISSION = 7382;
    private static final int REQUEST_ENABLE_BT = 7383;

    private TextureView textureView;
    private Surface videoSurface;
    private FrameLayout videoFrame;
    private int streamWidth;
    private int streamHeight;
    private TextView status;
    private TextView gamepadStatus;
    private Button gamepadButton;
    private GamepadOverlayView gamepadOverlay;
    private GamepadController bluetoothGamepad;
    private String host;
    private String sessionKey = "";
    private boolean audioEnabled;
    private int videoPort = VIDEO_PORT;
    private int audioPort = AUDIO_PORT;
    private int controlPort = CONTROL_PORT;
    private String viewerAddress = "IP belum tersedia";

    private volatile boolean running = false;
    private volatile Socket videoSocket;
    private volatile Socket audioSocket;
    private volatile Socket controlSocket;
    private volatile DataOutputStream controlOutput;
    private volatile Thread receiveThread;
    private volatile Thread audioThread;
    private volatile Thread controlThread;
    private volatile boolean controlLoopRunning = true;
    private volatile String videoStatus = "Menyiapkan koneksi...";
    private volatile String audioStatus = "Audio: nonaktif";
    private volatile String controlStatus = "Remote Control: menghubungkan";
    private float touchDownX;
    private float touchDownY;
    private long touchDownTime;

    private int dp(float value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        host = getIntent().getStringExtra("host");
        sessionKey = getIntent().getStringExtra("sessionKey");
        audioEnabled = getIntent().getBooleanExtra("audioEnabled", false);
        videoPort = getIntent().getIntExtra("videoPort", VIDEO_PORT);
        audioPort = getIntent().getIntExtra("audioPort", AUDIO_PORT);
        controlPort = getIntent().getIntExtra("controlPort", CONTROL_PORT);
        viewerAddress = NetworkAddress.getLocalIpv4(this);

        getWindow().setStatusBarColor(Color.BLACK);
        getWindow().setNavigationBarColor(Color.BLACK);
        enableImmersiveViewer();

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);

        status = new TextView(this);
        status.setTextColor(Color.WHITE);
        status.setTextSize(11);
        status.setMaxLines(3);
        status.setGravity(Gravity.CENTER_VERTICAL);
        status.setPadding(dp(12), dp(5), dp(12), dp(5));
        FrameLayout.LayoutParams statusParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(52), Gravity.TOP);
        status.setBackgroundColor(0x99000000);
        root.addView(status, statusParams);

        TextView touchHint = new TextView(this);
        touchHint.setText("Gamepad nyata: analog, A/B/X/Y, L1/R1, SELECT/START • sentuh area kosong untuk tap");
        touchHint.setTextColor(Color.rgb(190, 205, 229));
        touchHint.setTextSize(11);
        touchHint.setGravity(Gravity.CENTER);
        touchHint.setPadding(dp(8), dp(4), dp(8), dp(4));
        touchHint.setVisibility(View.GONE);

        LinearLayout gamepadBar = new LinearLayout(this);
        gamepadBar.setOrientation(LinearLayout.HORIZONTAL);
        gamepadBar.setGravity(Gravity.CENTER_VERTICAL);
        gamepadBar.setPadding(dp(8), 0, dp(8), 0);
        gamepadStatus = new TextView(this);
        gamepadStatus.setText("Bluetooth: belum tersambung");
        gamepadStatus.setTextColor(Color.rgb(230, 238, 250));
        gamepadStatus.setTextSize(9);
        gamepadStatus.setMaxLines(2);
        gamepadStatus.setMaxWidth(dp(190));
        gamepadBar.addView(gamepadStatus, new LinearLayout.LayoutParams(
                dp(180), ViewGroup.LayoutParams.MATCH_PARENT));
        gamepadButton = new Button(this);
        gamepadButton.setText("AKTIFKAN BT");
        gamepadButton.setTextSize(9);
        gamepadButton.setMinHeight(dp(34));
        gamepadButton.setMinimumHeight(dp(34));
        gamepadBar.addView(gamepadButton, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(36)));
        gamepadButton.setOnClickListener(v -> onGamepadButtonPressed());
        gamepadBar.setBackgroundColor(0xBB111923);
        gamepadBar.setPadding(dp(8), 0, dp(8), 0);

        videoFrame = new FrameLayout(this);
        videoFrame.setBackgroundColor(Color.BLACK);
        textureView = new TextureView(this);
        textureView.setOpaque(true);
        textureView.setSurfaceTextureListener(this);
        videoFrame.addView(textureView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT,
                Gravity.CENTER));
        videoFrame.addOnLayoutChangeListener((view, left, top, right, bottom,
                                               oldLeft, oldTop, oldRight, oldBottom) ->
                updateVideoSurfaceLayout());

        gamepadOverlay = new GamepadOverlayView(this);
        gamepadOverlay.setListener(new GamepadOverlayView.Listener() {
            @Override
            public void onGamepadState(int buttons, float leftX, float leftY) {
                GamepadController controller = bluetoothGamepad;
                if (controller != null && controller.isConnected()) {
                    controller.sendState(buttons, leftX, leftY);
                }
            }

            @Override
            public void onDirectGesture(int type, float x1, float y1,
                                        float x2, float y2, int durationMs) {
                sendRemoteGesture(type, x1, y1, x2, y2, durationMs);
            }
        });
        videoFrame.addView(gamepadOverlay, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        videoFrame.setClipChildren(true);
        videoFrame.setClipToPadding(true);
        root.addView(videoFrame, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // This was previously created but hidden and never attached to the root.
        // Keep Bluetooth setup reachable so the virtual HID device can actually connect.
        FrameLayout.LayoutParams gamepadBarParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(44), Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        gamepadBarParams.topMargin = dp(48);
        root.addView(gamepadBar, gamepadBarParams);

        Button overlayToggle = new Button(this);
        overlayToggle.setText("SEMBUNYIKAN GAMEPAD");
        overlayToggle.setTextSize(10);
        FrameLayout.LayoutParams toggleParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(42), Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        toggleParams.bottomMargin = dp(10);
        root.addView(overlayToggle, toggleParams);
        overlayToggle.setOnClickListener(button -> {
            boolean visible = gamepadOverlay.getVisibility() == View.VISIBLE;
            gamepadOverlay.setVisibility(visible ? View.GONE : View.VISIBLE);
            overlayToggle.setText(visible ? "TAMPILKAN GAMEPAD" : "SEMBUNYIKAN GAMEPAD");
            if (!visible) gamepadOverlay.releaseAllInputs();
        });

        setContentView(root);

        videoStatus = "Menghubungkan ke Host " + host + "...";
        updateStatus();
    }

    private void onGamepadButtonPressed() {
        if (Build.VERSION.SDK_INT < 28) {
            gamepadStatus.setText("Bluetooth Gamepad membutuhkan Android 9+ pada HP Viewer.");
            return;
        }

        if (bluetoothGamepad != null && bluetoothGamepad.isConnected()) {
            gamepadStatus.setText("Gamepad TERHUBUNG. Input virtual siap dikirim ke HP Host.");
            return;
        }
        if (bluetoothGamepad != null && bluetoothGamepad.isRegistered()) {
            showPairedHostDevices();
            return;
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            List<String> missing = new ArrayList<>();
            if (checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)
                    != PackageManager.PERMISSION_GRANTED) {
                missing.add(Manifest.permission.BLUETOOTH_CONNECT);
            }
            if (checkSelfPermission(Manifest.permission.BLUETOOTH_ADVERTISE)
                    != PackageManager.PERMISSION_GRANTED) {
                missing.add(Manifest.permission.BLUETOOTH_ADVERTISE);
            }
            if (!missing.isEmpty()) {
                requestPermissions(missing.toArray(new String[0]), REQUEST_BT_PERMISSION);
                return;
            }
        }
        ensureBluetoothEnabled();
    }

    private void ensureBluetoothEnabled() {
        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        if (adapter == null) {
            gamepadStatus.setText("HP Viewer tidak memiliki Bluetooth.");
            return;
        }
        try {
            if (!adapter.isEnabled()) {
                startActivityForResult(
                        new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE),
                        REQUEST_ENABLE_BT);
                return;
            }
        } catch (SecurityException error) {
            gamepadStatus.setText("Izin Bluetooth belum diberikan.");
            return;
        }
        startBluetoothGamepadController();
    }

    private void startBluetoothGamepadController() {
        if (bluetoothGamepad == null) {
            try {
                GamepadController.Listener listener = this::updateGamepadStatus;
                Class<?> implementation = Class.forName(
                        "com.offlinemirror.app.BluetoothHidGamepadApi28");
                bluetoothGamepad = (GamepadController) implementation
                        .getConstructor(Activity.class, GamepadController.Listener.class)
                        .newInstance(this, listener);
            } catch (Exception error) {
                gamepadStatus.setText("Mode Gamepad Bluetooth tidak tersedia: "
                        + error.getClass().getSimpleName());
                return;
            }
        }
        bluetoothGamepad.start();
    }

    private void updateGamepadStatus(String message) {
        runOnUiThread(() -> {
            if (gamepadStatus != null) gamepadStatus.setText(message);
            if (gamepadButton != null) {
                String lower = message.toLowerCase(java.util.Locale.ROOT);
                gamepadButton.setText(message.contains("TERHUBUNG") ? "TERHUBUNG"
                        : (lower.contains("terdaftar") ? "PILIH HOST"
                        : (lower.contains("menghubungkan") ? "MENYAMBUNG..." : "AKTIFKAN BT")));
            }
        });
    }

    private void showPairedHostDevices() {
        if (bluetoothGamepad == null) return;
        List<BluetoothDevice> devices = bluetoothGamepad.getBondedDevices();
        if (devices.isEmpty()) {
            new AlertDialog.Builder(this)
                    .setTitle("Pasangkan HP Host")
                    .setMessage("Di HP yang menjadi Host, buka Pengaturan > Bluetooth. Pasangkan perangkat bernama 'OfflineMirror Gamepad'. Setelah berhasil dipasangkan, kembali ke Viewer dan tekan PILIH HP HOST.")
                    .setPositiveButton("Mengerti", null)
                    .show();
            return;
        }

        String[] labels = new String[devices.size()];
        for (int i = 0; i < devices.size(); i++) {
            BluetoothDevice device = devices.get(i);
            String name;
            try {
                name = device.getName();
            } catch (SecurityException error) {
                name = null;
            }
            labels[i] = (name == null || name.trim().isEmpty()
                    ? "Perangkat Bluetooth " + (i + 1) : name)
                    + "\\n" + device.getAddress();
        }

        new AlertDialog.Builder(this)
                .setTitle("Pilih HP Host sebagai Gamepad")
                .setItems(labels, (dialog, which) -> {
                    if (which >= 0 && which < devices.size()) {
                        bluetoothGamepad.connect(devices.get(which));
                    }
                })
                .setNegativeButton("Batal", null)
                .show();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions,
                                           int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_BT_PERMISSION) {
            boolean granted = grantResults.length > 0;
            for (int result : grantResults) {
                granted &= result == PackageManager.PERMISSION_GRANTED;
            }
            if (granted) {
                ensureBluetoothEnabled();
            } else if (gamepadStatus != null) {
                gamepadStatus.setText("Izin Bluetooth diperlukan untuk Gamepad.");
            }
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_ENABLE_BT) {
            if (resultCode == RESULT_OK) {
                startBluetoothGamepadController();
            } else if (gamepadStatus != null) {
                gamepadStatus.setText("Bluetooth tidak diaktifkan.");
            }
        } else if (requestCode == GamepadController.REQUEST_DISCOVERABLE) {
            if (resultCode > 0 && gamepadStatus != null) {
                gamepadStatus.setText("HP Viewer terlihat. Pasangkan 'OfflineMirror Gamepad' dari HP Host.");
            } else if (gamepadStatus != null) {
                gamepadStatus.setText("Mode dapat ditemukan ditolak. Aktifkan Bluetooth pairing secara manual.");
            }
        }
    }

    private void enableImmersiveViewer() {
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_FULLSCREEN);
    }

    @Override
    public void onSurfaceTextureAvailable(SurfaceTexture surfaceTexture, int width, int height) {
        videoSurface = new Surface(surfaceTexture);
        updateVideoSurfaceLayout();
        startReceiving(videoSurface);
        startControlConnection();
    }

    @Override
    public void onSurfaceTextureSizeChanged(SurfaceTexture surfaceTexture, int width, int height) {
        updateVideoSurfaceLayout();
    }

    @Override
    public boolean onSurfaceTextureDestroyed(SurfaceTexture surfaceTexture) {
        stopReceiving();
        // The decoder thread releases its Surface after MediaCodec has stopped.
        videoSurface = null;
        return true;
    }

    @Override
    public void onSurfaceTextureUpdated(SurfaceTexture surfaceTexture) {
        // Rendering is handled directly by MediaCodec.
    }

    private synchronized void startReceiving(Surface surface) {
        if (surface == null || !surface.isValid() || running) return;
        running = true;
        receiveThread = new Thread(() -> receiveLoop(surface), "OfflineMirror-Viewer");
        receiveThread.start();
    }

    private void startControlConnection() {
        if (controlThread != null && controlThread.isAlive()) return;
        controlLoopRunning = true;
        controlThread = new Thread(() -> {
            while (controlLoopRunning) {
                Socket candidate = new Socket();
                try {
                    candidate.setTcpNoDelay(true);
                    candidate.setKeepAlive(true);
                    candidate.connect(new InetSocketAddress(host, controlPort), 8_000);

                    DataOutputStream output = new DataOutputStream(
                            new BufferedOutputStream(candidate.getOutputStream()));
                    output.writeUTF(sessionKey);
                    output.flush();

                    DataInputStream input = new DataInputStream(
                            new BufferedInputStream(candidate.getInputStream()));
                    int protocol = input.readInt();
                    if (protocol != CONTROL_MAGIC) {
                        throw new IllegalStateException("Versi protokol kontrol Host tidak cocok");
                    }

                    boolean hostAccessibilityEnabled = input.readBoolean();
                    controlSocket = candidate;
                    controlOutput = output;
                    controlStatus = hostAccessibilityEnabled
                            ? "Remote Control aktif • sentuh gambar"
                            : "Aksesibilitas belum aktif pada HP Host";
                    updateStatus();
                    android.util.Log.i("OfflineMirror", "Remote Control channel authenticated");

                    // The Host reports its Accessibility status after every gesture.
                    while (controlLoopRunning && !candidate.isClosed()) {
                        int state = input.readUnsignedByte();
                        if (state != 0 && state != 1) {
                            throw new IllegalStateException("Status kontrol dari Host tidak valid");
                        }
                        controlStatus = state == 1
                                ? "Remote Control aktif • sentuh gambar"
                                : "Aktifkan Aksesibilitas pada HP Host";
                        updateStatus();
                    }
                } catch (Exception error) {
                    if (controlLoopRunning) {
                        controlStatus = "Kontrol terputus • mencoba menyambungkan ulang";
                        updateStatus();
                        android.util.Log.w("OfflineMirror",
                                "Remote Control retry: " + error.getMessage());
                    }
                } finally {
                    if (controlSocket == candidate) {
                        controlSocket = null;
                        controlOutput = null;
                    }
                    try { candidate.close(); } catch (Exception ignored) {}
                }

                if (controlLoopRunning) {
                    try {
                        Thread.sleep(1200);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        controlLoopRunning = false;
                    }
                }
            }
        }, "OfflineMirror-Remote-Control-Client");
        controlThread.setDaemon(true);
        controlThread.start();
    }

    private boolean handleRemoteTouch(View view, MotionEvent event) {
        if (event.getAction() == MotionEvent.ACTION_DOWN) {
            touchDownX = event.getX();
            touchDownY = event.getY();
            touchDownTime = event.getEventTime();
            return true;
        }

        if (event.getAction() == MotionEvent.ACTION_UP) {
            float width = Math.max(1f, view.getWidth());
            float height = Math.max(1f, view.getHeight());
            float x1 = clamp(touchDownX / width);
            float y1 = clamp(touchDownY / height);
            float x2 = clamp(event.getX() / width);
            float y2 = clamp(event.getY() / height);
            long duration = Math.max(1L, event.getEventTime() - touchDownTime);
            float distance = (float) Math.hypot(
                    event.getX() - touchDownX, event.getY() - touchDownY);

            if (distance >= dp(20)) {
                sendRemoteGesture(3, x1, y1, x2, y2, (int) duration);
            } else if (duration >= 500) {
                sendRemoteGesture(2, x1, y1, x1, y1, (int) duration);
            } else {
                sendRemoteGesture(1, x1, y1, x1, y1, 90);
            }
            return true;
        }

        // Continue consuming the complete touch sequence on the video surface.
        return true;
    }

    private void sendRemoteGesture(int type, float x1, float y1,
                                   float x2, float y2, int duration) {
        DataOutputStream output = controlOutput;
        if (output == null) {
            controlStatus = "Remote Control sedang menyambungkan ke Host";
            updateStatus();
            return;
        }
        try {
            synchronized (output) {
                output.writeByte(type);
                output.writeFloat(x1);
                output.writeFloat(y1);
                if (type == 3) {
                    output.writeFloat(x2);
                    output.writeFloat(y2);
                }
                output.writeInt(Math.max(60, Math.min(1200, duration)));
                output.flush();
            }
        } catch (Exception error) {
            controlOutput = null;
            controlStatus = "Remote Control terputus • menyambungkan ulang";
            try { if (controlSocket != null) controlSocket.close(); } catch (Exception ignored) {}
            updateStatus();
            android.util.Log.w("OfflineMirror", "Could not send remote gesture", error);
        }
    }

    private float clamp(float value) {
        return Math.max(0f, Math.min(1f, value));
    }

    /**
     * TextureView lets the decoded image be transformed independently of the
     * View bounds. Map the full texture to a centered, aspect-correct crop:
     * no non-uniform stretching, with only the excess edges cropped.
     */
    private void setStreamDimensions(StreamConfig config) {
        streamWidth = config.width;
        streamHeight = config.height;
        final int targetOrientation = config.width > config.height
                ? ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                : ActivityInfo.SCREEN_ORIENTATION_PORTRAIT;
        runOnUiThread(() -> {
            // The Viewer follows the Host stream's aspect orientation. This avoids
            // rotating the decoded frame in two separate places on two devices.
            if (getRequestedOrientation() != targetOrientation) {
                setRequestedOrientation(targetOrientation);
            }
            updateVideoSurfaceLayout();
        });
    }

    private void updateVideoSurfaceLayout() {
        if (videoFrame == null || textureView == null) return;
        int viewWidth = videoFrame.getWidth();
        int viewHeight = videoFrame.getHeight();
        if (viewWidth <= 0 || viewHeight <= 0) return;

        if (streamWidth < 2 || streamHeight < 2) {
            textureView.setTransform(new Matrix());
            if (gamepadOverlay != null) {
                gamepadOverlay.setVideoTransform(0f, 0f, viewWidth, viewHeight, 0);
            }
            return;
        }

        // Host orientation is applied to this Activity when each stream header
        // arrives. Keep decoded pixels upright; only scale/crop to the Viewer bounds.
        final int rotation = 0;
        float displaySourceWidth = streamWidth;
        float displaySourceHeight = streamHeight;
        float scale = Math.max(
                (float) viewWidth / displaySourceWidth,
                (float) viewHeight / displaySourceHeight);
        float targetWidth = displaySourceWidth * scale;
        float targetHeight = displaySourceHeight * scale;
        float left = (viewWidth - targetWidth) * 0.5f;
        float top = (viewHeight - targetHeight) * 0.5f;
        float right = left + targetWidth;
        float bottom = top + targetHeight;

        // TextureView initially maps its buffer into the View rectangle. This
        // matrix maps that rectangle into the correctly proportioned crop.
        float[] source = {
                0f, 0f,
                viewWidth, 0f,
                viewWidth, viewHeight,
                0f, viewHeight
        };
        float[] destination = {
                left, top,
                right, top,
                right, bottom,
                left, bottom
        };

        Matrix transform = new Matrix();
        if (transform.setPolyToPoly(source, 0, destination, 0, 4)) {
            textureView.setTransform(transform);
        }

        if (gamepadOverlay != null) {
            gamepadOverlay.setVideoTransform(
                    left, top, targetWidth, targetHeight, rotation);
        }
    }

    private void receiveLoop(Surface surface) {
        MediaCodec decoder = null;
        DataInputStream input = null;
        long decodedFrames = 0;
        long receivedBytes = 0;
        try {
            videoSocket = new Socket();
            videoSocket.setTcpNoDelay(true);
            // Small socket receive window limits stale-frame queueing on Wi-Fi/hotspot.
            videoSocket.setReceiveBufferSize(64 * 1024);
            videoSocket.connect(new InetSocketAddress(host, videoPort), 10_000);
            authenticateSocket(videoSocket);
            input = new DataInputStream(
                    new BufferedInputStream(videoSocket.getInputStream(), 8 * 1024));

            StreamConfig config = readStreamHeader(input);
            setStreamDimensions(config);
            decoder = createDecoder(config, surface);
            videoStatus = "Decoder siap " + config.width + " × " + config.height
                    + ". Menunggu frame...";
            updateStatus();

            if (audioEnabled) startAudioPlayback();

            MediaCodec.BufferInfo outputInfo = new MediaCodec.BufferInfo();
            while (running) {
                int size = input.readInt();

                // Kept for protocol compatibility. The app is portrait-locked, so
                // the Host should normally send only one fixed-size stream header.
                if (size == -1) {
                    decoder = releaseDecoder(decoder);
                    config = readStreamHeader(input);
                    setStreamDimensions(config);
                    decoder = createDecoder(config, surface);
                    videoStatus = "Stream baru " + config.width + " × " + config.height;
                    updateStatus();
                    continue;
                }

                int flags = input.readInt();
                long presentationTimeUs = input.readLong();
                if (size <= 0 || size > 8 * 1024 * 1024) {
                    throw new IllegalStateException("Ukuran paket H.264 tidak valid: " + size);
                }

                byte[] packet = new byte[size];
                input.readFully(packet);
                receivedBytes += size;

                // The Host transports SPS/PPS in the stream header, not as packets.
                if ((flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0
                        && (config.csd0.length > 0 || config.csd1.length > 0)) {
                    continue;
                }

                int inputIndex = decoder.dequeueInputBuffer(1_000);
                while (running && inputIndex < 0) {
                    inputIndex = decoder.dequeueInputBuffer(1_000);
                }
                if (!running || inputIndex < 0) break;

                ByteBuffer inputBuffer = decoder.getInputBuffer(inputIndex);
                if (inputBuffer == null || packet.length > inputBuffer.capacity()) {
                    throw new IllegalStateException("Buffer input decoder terlalu kecil");
                }
                inputBuffer.clear();
                inputBuffer.put(packet);
                decoder.queueInputBuffer(inputIndex, 0, packet.length,
                        presentationTimeUs, flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM);

                // Drain every decoded output. Surface decoders must be given every
                // output buffer for rendering; do not gate render=true on BufferInfo.size.
                int outputIndex = decoder.dequeueOutputBuffer(outputInfo, 1_000);
                while (outputIndex >= 0 && running) {
                    decoder.releaseOutputBuffer(outputIndex, true);
                    decodedFrames++;
                    if (decodedFrames == 1 || decodedFrames % 30 == 0) {
                        videoStatus = "Mirroring aktif • " + config.width + " × " + config.height
                                + " • " + decodedFrames + " frame";
                        updateStatus();
                        android.util.Log.i("OfflineMirror", "Decoded frames=" + decodedFrames
                                + " receivedBytes=" + receivedBytes);
                    }
                    outputIndex = decoder.dequeueOutputBuffer(outputInfo, 0);
                }
                if (outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    android.util.Log.i("OfflineMirror",
                            "Decoder output format: " + decoder.getOutputFormat());
                }
            }
        } catch (Exception error) {
            android.util.Log.e("OfflineMirror", "Viewer H.264 pipeline failed", error);
            if (running) {
                videoStatus = "Video gagal: " + error.getClass().getSimpleName()
                        + " • " + error.getMessage();
                updateStatus();
            }
        } finally {
            running = false;
            try { if (input != null) input.close(); } catch (Exception ignored) {}
            try { if (videoSocket != null) videoSocket.close(); } catch (Exception ignored) {}
            releaseDecoder(decoder);
            try { surface.release(); } catch (Exception ignored) {}
        }
    }

    private void authenticateSocket(Socket socket) throws Exception {
        if (sessionKey == null || sessionKey.length() < 16) {
            throw new IllegalStateException("Pairing key tidak tersedia. Scan QR Host lagi.");
        }
        DataOutputStream auth = new DataOutputStream(socket.getOutputStream());
        auth.writeUTF(sessionKey);
        auth.flush();
    }

    private void startAudioPlayback() {
        if (audioThread != null && audioThread.isAlive()) return;
        audioThread = new Thread(this::receiveAudio, "OfflineMirror-Audio-Player");
        audioThread.start();
    }

    private void receiveAudio() {
        AudioTrack track = null;
        DataInputStream input = null;
        try {
            audioSocket = new Socket();
            audioSocket.setTcpNoDelay(true);
            audioSocket.connect(new InetSocketAddress(host, audioPort), 12_000);
            authenticateSocket(audioSocket);
            input = new DataInputStream(
                    new BufferedInputStream(audioSocket.getInputStream(), 32 * 1024));

            int sampleRate = input.readInt();
            int channels = input.readInt();
            int encoding = input.readInt();
            if (sampleRate < 8000 || sampleRate > 96000 || (channels != 1 && channels != 2)) {
                throw new IllegalStateException("Format audio Host tidak valid");
            }

            int channelMask = channels == 1
                    ? AudioFormat.CHANNEL_OUT_MONO : AudioFormat.CHANNEL_OUT_STEREO;
            int minBuffer = AudioTrack.getMinBufferSize(
                    sampleRate, channelMask, AudioFormat.ENCODING_PCM_16BIT);
            int bufferSize = Math.max(8192, minBuffer > 0 ? minBuffer * 2 : 16384);

            track = new AudioTrack.Builder()
                    .setAudioAttributes(new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                            .build())
                    .setAudioFormat(new AudioFormat.Builder()
                            .setEncoding(encoding)
                            .setSampleRate(sampleRate)
                            .setChannelMask(channelMask)
                            .build())
                    .setBufferSizeInBytes(bufferSize)
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build();

            if (track.getState() != AudioTrack.STATE_INITIALIZED) {
                throw new IllegalStateException("Output audio tidak dapat diinisialisasi");
            }
            track.play();
            audioStatus = "Audio: terhubung";
            updateStatus();

            while (running) {
                int count = input.readInt();
                if (count <= 0 || count > 65536) {
                    throw new IllegalStateException("Paket audio tidak valid");
                }
                byte[] pcm = new byte[count];
                input.readFully(pcm);
                int offset = 0;
                while (offset < count && running) {
                    int written = track.write(pcm, offset, count - offset,
                            AudioTrack.WRITE_BLOCKING);
                    if (written < 0) throw new IllegalStateException("Output audio terhenti");
                    offset += written;
                }
            }
        } catch (Exception error) {
            if (running) {
                audioStatus = "Audio tidak tersedia";
                updateStatus();
                android.util.Log.w("OfflineMirror", "Viewer audio failed", error);
            }
        } finally {
            if (track != null) {
                try { track.pause(); } catch (Exception ignored) {}
                try { track.flush(); } catch (Exception ignored) {}
                try { track.release(); } catch (Exception ignored) {}
            }
            try { if (input != null) input.close(); } catch (Exception ignored) {}
            try { if (audioSocket != null) audioSocket.close(); } catch (Exception ignored) {}
        }
    }

    private StreamConfig readStreamHeader(DataInputStream input) throws Exception {
        int magic = input.readInt();
        if (magic != MAGIC) throw new IllegalStateException("Header stream tidak dikenal");
        int width = input.readInt();
        int height = input.readInt();
        if (width < 2 || height < 2 || width > 4096 || height > 4096) {
            throw new IllegalStateException("Resolusi stream tidak valid");
        }
        byte[] csd0 = readConfig(input);
        byte[] csd1 = readConfig(input);
        if (csd0.length == 0 && csd1.length == 0) {
            throw new IllegalStateException("Konfigurasi H.264 Host kosong");
        }
        return new StreamConfig(width, height, csd0, csd1);
    }

    private MediaCodec createDecoder(StreamConfig config, Surface surface) throws Exception {
        if (surface == null || !surface.isValid()) {
            throw new IllegalStateException("Surface Viewer belum siap");
        }
        MediaFormat format = MediaFormat.createVideoFormat(
                MediaFormat.MIMETYPE_VIDEO_AVC, config.width, config.height);
        if (config.csd0.length > 0) {
            format.setByteBuffer("csd-0", ByteBuffer.wrap(config.csd0));
        }
        if (config.csd1.length > 0) {
            format.setByteBuffer("csd-1", ByteBuffer.wrap(config.csd1));
        }

        MediaCodec decoder = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC);
        try {
            decoder.configure(format, surface, null, 0);
            decoder.start();
            if (Build.VERSION.SDK_INT >= 30) {
                android.os.Bundle lowLatency = new android.os.Bundle();
                lowLatency.putInt(MediaCodec.PARAMETER_KEY_LOW_LATENCY, 1);
                try {
                    decoder.setParameters(lowLatency);
                    android.util.Log.i("OfflineMirror", "Enabled decoder low-latency mode");
                } catch (Exception unsupported) {
                    android.util.Log.w("OfflineMirror", "Decoder low-latency mode unavailable", unsupported);
                }
            }
            android.util.Log.i("OfflineMirror", "Using decoder " + decoder.getName()
                    + " for " + config.width + "x" + config.height);
            return decoder;
        } catch (Exception error) {
            try { decoder.release(); } catch (Exception ignored) {}
            throw new IllegalStateException("Decoder AVC gagal: " + error.getMessage(), error);
        }
    }

    private MediaCodec releaseDecoder(MediaCodec decoder) {
        if (decoder != null) {
            try { decoder.stop(); } catch (Exception ignored) {}
            try { decoder.release(); } catch (Exception ignored) {}
        }
        return null;
    }

    private byte[] readConfig(DataInputStream input) throws Exception {
        int size = input.readInt();
        if (size < 0 || size > 1024 * 1024) {
            throw new IllegalStateException("Header codec rusak");
        }
        byte[] bytes = new byte[size];
        input.readFully(bytes);
        return bytes;
    }

    private void updateStatus() {
        runOnUiThread(() -> {
            if (status != null) {
                status.setText("Viewer: " + viewerAddress + "  |  Host: " + host + "\n"
                        + videoStatus + "  •  " + audioStatus + "  •  " + controlStatus);
            }
        });
    }

    private void stopReceiving() {
        running = false;
        try { if (videoSocket != null) videoSocket.close(); } catch (Exception ignored) {}
        try { if (audioSocket != null) audioSocket.close(); } catch (Exception ignored) {}
        // Remote Control is a separate channel; do not tear it down when the
        // video Surface is recreated.
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) enableImmersiveViewer();
    }

    @Override
    protected void onDestroy() {
        controlLoopRunning = false;
        stopReceiving();
        controlOutput = null;
        try { if (controlSocket != null) controlSocket.close(); } catch (Exception ignored) {}
        if (bluetoothGamepad != null) bluetoothGamepad.close();
        super.onDestroy();
    }

    private static final class StreamConfig {
        final int width;
        final int height;
        final byte[] csd0;
        final byte[] csd1;

        StreamConfig(int width, int height, byte[] csd0, byte[] csd1) {
            this.width = width;
            this.height = height;
            this.csd0 = csd0;
            this.csd1 = csd1;
        }
    }
}
