package com.offlinemirror.app;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.os.Bundle;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;

public class ViewerActivity extends Activity implements SurfaceHolder.Callback {
    private static final int VIDEO_PORT = 48888;
    private static final int AUDIO_PORT = 48889;
    private static final int CONTROL_PORT = 48890;
    private static final int MAGIC_JPEG = 0x4F4D4A50;

    private SurfaceView surfaceView;
    private TextView status;
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
    private Thread receiveThread;
    private Thread audioThread;
    private final Object receiverLock = new Object();
    private Surface pendingSurface;
    private boolean restartWorkerRunning = false;
    private boolean activityDestroyed = false;

    private float touchDownX;
    private float touchDownY;
    private long touchDownTime;

    private volatile String videoStatus = "Menyiapkan koneksi...";
    private volatile String audioStatus = "Audio: waiting";
    private volatile String controlStatus = "Touch: connecting";

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

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.BLACK);

        status = new TextView(this);
        status.setTextColor(Color.WHITE);
        status.setTextSize(11);
        status.setMaxLines(3);
        status.setGravity(Gravity.CENTER_VERTICAL);
        status.setPadding(dp(12), dp(5), dp(12), dp(5));
        root.addView(status, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(66)));

        surfaceView = new SurfaceView(this);
        surfaceView.setBackgroundColor(Color.BLACK);
        root.addView(surfaceView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        surfaceView.getHolder().addCallback(this);
        surfaceView.setOnTouchListener(this::handleViewerTouch);
        setContentView(root);
        videoStatus = "Menghubungkan ke " + host + "...";
        updateStatus();
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
    public void surfaceCreated(SurfaceHolder holder) {
        startReceiving(holder.getSurface());
    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
        // The drawing code uses the current Canvas dimensions on every frame.
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        stopReceiving();
    }

    private void startReceiving(Surface surface) {
        if (surface == null || !surface.isValid()) return;
        synchronized (receiverLock) {
            if (activityDestroyed) return;
            pendingSurface = surface;

            // A restart worker waits for the old receiver and then uses the newest Surface.
            if (restartWorkerRunning) return;

            if (receiveThread == null || !receiveThread.isAlive()) {
                launchReceiverLocked(surface);
                return;
            }

            videoStatus = "Orientasi layar berubah. Menyambungkan ulang video...";
            updateStatus();
            stopReceiverLocked();

            restartWorkerRunning = true;
            Thread previousReceiver = receiveThread;
            Thread restartWorker = new Thread(() -> {
                try {
                    previousReceiver.join();
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                }

                synchronized (receiverLock) {
                    restartWorkerRunning = false;
                    Surface latestSurface = pendingSurface;
                    if (!activityDestroyed && latestSurface != null && latestSurface.isValid()
                            && !previousReceiver.isAlive()) {
                        launchReceiverLocked(latestSurface);
                    }
                }
            }, "OfflineMirror-Viewer-Restart");
            restartWorker.start();
        }
    }

    private void launchReceiverLocked(Surface surface) {
        running = true;
        receiveThread = new Thread(() -> receiveLoop(surface), "OfflineMirror-Viewer");
        receiveThread.start();
    }

    private void stopReceiverLocked() {
        running = false;
        try { if (videoSocket != null) videoSocket.close(); } catch (Exception ignored) {}
        closeAuxiliaryConnections();
    }

    private void receiveLoop(Surface surface) {
        DataInputStream input = null;
        try {
            videoSocket = new Socket();
            videoSocket.setTcpNoDelay(true);
            videoSocket.connect(new InetSocketAddress(host, videoPort), 10_000);
            authenticateSocket(videoSocket);
            videoStatus = "Video tersambung. Menunggu stream Host...";
            updateStatus();

            input = new DataInputStream(
                    new BufferedInputStream(videoSocket.getInputStream(), 512 * 1024));
            JpegStreamConfig config = readJpegStreamHeader(input);
            videoStatus = "Stream gambar siap " + config.width + " × " + config.height
                    + " @" + config.fps + "fps. Menunggu frame...";
            updateStatus();

            connectControl();
            if (audioEnabled) startAudioPlayback();

            boolean firstFrameRendered = false;
            long receivedPackets = 0;
            long receivedBytes = 0;
            while (running) {
                int size = input.readInt();
                if (size == -1) {
                    config = readJpegStreamHeader(input);
                    firstFrameRendered = false;
                    videoStatus = "Orientasi berubah: " + config.width + " × " + config.height;
                    updateStatus();
                    continue;
                }
                if (size <= 0 || size > 12 * 1024 * 1024) {
                    throw new IllegalStateException("Ukuran frame JPEG tidak valid: " + size);
                }
                long presentationTimeUs = input.readLong();
                byte[] packet = new byte[size];
                input.readFully(packet);
                Bitmap frame = BitmapFactory.decodeByteArray(packet, 0, packet.length);
                if (frame == null) {
                    throw new IllegalStateException("Viewer tidak dapat membaca frame JPEG");
                }
                renderJpegFrame(surface, frame);
                frame.recycle();

                receivedPackets++;
                receivedBytes += size;
                if (!firstFrameRendered) {
                    firstFrameRendered = true;
                    videoStatus = "Mirroring aktif • " + config.width + " × " + config.height;
                    updateStatus();
                }
                if (receivedPackets == 1 || receivedPackets % 60 == 0) {
                    android.util.Log.i("OfflineMirror", "Viewer JPEG frames=" + receivedPackets
                            + " bytes=" + receivedBytes + " lastPtsUs=" + presentationTimeUs);
                }
            }
        } catch (Exception error) {
            android.util.Log.e("OfflineMirror", "Viewer JPEG stream failed", error);
            if (running) {
                videoStatus = "Video gagal: " + error.getMessage();
                updateStatus();
            }
        } finally {
            running = false;
            try { if (input != null) input.close(); } catch (Exception ignored) {}
            try { if (videoSocket != null) videoSocket.close(); } catch (Exception ignored) {}
            closeAuxiliaryConnections();
        }
    }

    private JpegStreamConfig readJpegStreamHeader(DataInputStream input) throws Exception {
        int magic = input.readInt();
        if (magic != MAGIC_JPEG) {
            throw new IllegalStateException("Host bukan OfflineMirror JPEG stream (magic mismatch)");
        }
        int width = input.readInt();
        int height = input.readInt();
        int fps = input.readInt();
        if (width < 2 || height < 2 || width > 4096 || height > 4096
                || fps < 1 || fps > 30) {
            throw new IllegalStateException("Header video Host tidak valid");
        }
        return new JpegStreamConfig(width, height, fps);
    }

    private void renderJpegFrame(Surface surface, Bitmap bitmap) throws Exception {
        if (surface == null || !surface.isValid()) {
            throw new IllegalStateException("Surface Viewer tidak aktif");
        }
        Canvas canvas = surface.lockCanvas(null);
        if (canvas == null) throw new IllegalStateException("Canvas Viewer tidak tersedia");
        try {
            canvas.drawColor(Color.BLACK);
            float scale = Math.min(
                    canvas.getWidth() / (float) bitmap.getWidth(),
                    canvas.getHeight() / (float) bitmap.getHeight());
            float width = bitmap.getWidth() * scale;
            float height = bitmap.getHeight() * scale;
            float left = (canvas.getWidth() - width) / 2f;
            float top = (canvas.getHeight() - height) / 2f;
            Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.DITHER_FLAG);
            canvas.drawBitmap(bitmap, null,
                    new RectF(left, top, left + width, top + height), paint);
        } finally {
            surface.unlockCanvasAndPost(canvas);
        }
    }

    private void authenticateSocket(Socket socket) throws Exception {
        if (sessionKey == null || sessionKey.length() < 16) {
            throw new IllegalStateException("Pairing key tidak tersedia. Scan ulang QR Host.");
        }
        DataOutputStream auth = new DataOutputStream(socket.getOutputStream());
        auth.writeUTF(sessionKey);
        auth.flush();
    }

    private void connectControl() {
        try {
            controlSocket = new Socket();
            controlSocket.setTcpNoDelay(true);
            controlSocket.connect(new InetSocketAddress(host, controlPort), 8_000);
            DataOutputStream output = new DataOutputStream(
                    new BufferedOutputStream(controlSocket.getOutputStream()));
            output.writeUTF(sessionKey);
            output.flush();
            controlOutput = output;
            controlStatus = "Touch: ready";
        } catch (Exception error) {
            controlStatus = "Touch unavailable";
        }
        updateStatus();
    }

    private boolean handleViewerTouch(View view, MotionEvent event) {
        if (event.getAction() == MotionEvent.ACTION_DOWN) {
            touchDownX = event.getX();
            touchDownY = event.getY();
            touchDownTime = event.getEventTime();
            return true;
        }
        if (event.getAction() == MotionEvent.ACTION_UP) {
            float upX = event.getX();
            float upY = event.getY();
            long duration = Math.max(1, event.getEventTime() - touchDownTime);
            float width = Math.max(1f, view.getWidth());
            float height = Math.max(1f, view.getHeight());
            float x1 = clamp(touchDownX / width);
            float y1 = clamp(touchDownY / height);
            float x2 = clamp(upX / width);
            float y2 = clamp(upY / height);
            float distance = (float) Math.hypot(upX - touchDownX, upY - touchDownY);

            if (duration >= 500 && distance < dp(20)) {
                sendTouchCommand(2, x1, y1, x1, y1, (int) duration);
            } else if (distance >= dp(20)) {
                sendTouchCommand(3, x1, y1, x2, y2, (int) duration);
            } else {
                sendTouchCommand(1, x1, y1, x1, y1, 90);
            }
            return true;
        }
        return true;
    }

    private void sendTouchCommand(int type, float x1, float y1,
                                  float x2, float y2, int duration) {
        DataOutputStream output = controlOutput;
        if (output == null) return;
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
            controlStatus = "Touch disconnected";
            controlOutput = null;
            updateStatus();
        }
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

            input = new DataInputStream(new BufferedInputStream(audioSocket.getInputStream(), 32 * 1024));

            int sampleRate = input.readInt();
            int channels = input.readInt();
            int encoding = input.readInt();
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
                throw new IllegalStateException("Audio output could not initialize");
            }
            track.play();
            audioStatus = "Audio: connected";
            updateStatus();

            while (running) {
                int count = input.readInt();
                if (count <= 0 || count > 65536) {
                    throw new IllegalStateException("Invalid audio packet");
                }
                byte[] pcm = new byte[count];
                input.readFully(pcm);
                int offset = 0;
                while (offset < count && running) {
                    int written = track.write(pcm, offset, count - offset, AudioTrack.WRITE_BLOCKING);
                    if (written < 0) throw new IllegalStateException("Audio output stopped");
                    offset += written;
                }
            }
        } catch (Exception error) {
            if (running) {
                audioStatus = "Audio unavailable";
                updateStatus();
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

    private float clamp(float value) {
        return Math.max(0f, Math.min(1f, value));
    }

    private void updateStatus() {
        runOnUiThread(() -> {
            if (status != null) {
                status.setText("HP ini (Viewer): " + viewerAddress
                        + "   |   IP Host: " + host + "\n"
                        + videoStatus + "  •  " + controlStatus + "  •  " + audioStatus);
            }
        });
    }

    private void closeAuxiliaryConnections() {
        try { if (audioSocket != null) audioSocket.close(); } catch (Exception ignored) {}
        try { if (controlSocket != null) controlSocket.close(); } catch (Exception ignored) {}
        controlOutput = null;
    }

    private void stopReceiving() {
        synchronized (receiverLock) {
            pendingSurface = null;
            stopReceiverLocked();
        }
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) enableImmersiveViewer();
    }

    @Override
    protected void onDestroy() {
        synchronized (receiverLock) {
            activityDestroyed = true;
            pendingSurface = null;
            stopReceiverLocked();
        }
        super.onDestroy();
    }

    private static final class JpegStreamConfig {
        final int width;
        final int height;
        final int fps;
        JpegStreamConfig(int width, int height, int fps) {
            this.width = width;
            this.height = height;
            this.fps = fps;
        }
    }
}
