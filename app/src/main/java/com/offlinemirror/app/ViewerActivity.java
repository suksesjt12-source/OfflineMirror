package com.offlinemirror.app;

import android.app.Activity;
import android.graphics.Color;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.media.MediaCodec;
import android.media.MediaFormat;
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
import java.nio.ByteBuffer;

public class ViewerActivity extends Activity implements SurfaceHolder.Callback {
    private static final int VIDEO_PORT = 48888;
    private static final int AUDIO_PORT = 48889;
    private static final int CONTROL_PORT = 48890;
    private static final int MAGIC = 0x4F4D4952;

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
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        stopReceiving();
    }

    private void startReceiving(Surface surface) {
        if (receiveThread != null && receiveThread.isAlive()) return;
        running = true;
        receiveThread = new Thread(() -> receiveLoop(surface), "OfflineMirror-Viewer");
        receiveThread.start();
    }

    private void receiveLoop(Surface surface) {
        MediaCodec decoder = null;
        DataInputStream input = null;
        try {
            videoSocket = new Socket();
            videoSocket.setTcpNoDelay(true);
            videoSocket.connect(new InetSocketAddress(host, videoPort), 10_000);
            authenticateSocket(videoSocket);
            videoStatus = "Terhubung. Menunggu encoder Host...";
            updateStatus();

            input = new DataInputStream(
                    new BufferedInputStream(videoSocket.getInputStream(), 256 * 1024));

            StreamConfig config = readStreamHeader(input);
            videoStatus = "Header diterima. Menyiapkan decoder...";
            updateStatus();
            decoder = createDecoder(config, surface);
            videoStatus = "Decoder siap " + config.width + " × " + config.height
                    + ". Menunggu frame pertama...";
            updateStatus();

            connectControl();
            if (audioEnabled) startAudioPlayback();

            MediaCodec.BufferInfo outputInfo = new MediaCodec.BufferInfo();
            boolean firstFrameRendered = false;
            while (running) {
                int size = input.readInt();

                // The Host sends -1 and a new header after changing screen orientation.
                if (size == -1) {
                    decoder = releaseDecoder(decoder);
                    config = readStreamHeader(input);
                    decoder = createDecoder(config, surface);
                    firstFrameRendered = false;
                    videoStatus = "Video " + config.width + " × " + config.height + " • rotated";
                    updateStatus();
                    continue;
                }

                int flags = input.readInt();
                long presentationTimeUs = input.readLong();
                if (size <= 0 || size > 8 * 1024 * 1024) {
                    throw new IllegalStateException("Invalid video packet");
                }
                byte[] packet = new byte[size];
                input.readFully(packet);

                int inputIndex;
                do {
                    inputIndex = decoder.dequeueInputBuffer(10_000);
                    if (!running) break;
                } while (inputIndex < 0);

                if (!running || inputIndex < 0) break;
                ByteBuffer inputBuffer = decoder.getInputBuffer(inputIndex);
                if (inputBuffer == null || packet.length > inputBuffer.capacity()) {
                    throw new IllegalStateException("Decoder input buffer is too small");
                }
                inputBuffer.clear();
                inputBuffer.put(packet);
                int decoderFlags = flags & (MediaCodec.BUFFER_FLAG_CODEC_CONFIG
                        | MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                decoder.queueInputBuffer(inputIndex, 0, packet.length,
                        presentationTimeUs, decoderFlags);

                int outputIndex = decoder.dequeueOutputBuffer(outputInfo, 10_000);
                while (outputIndex >= 0 && running) {
                    decoder.releaseOutputBuffer(outputIndex, true);
                    if (!firstFrameRendered) {
                        firstFrameRendered = true;
                        videoStatus = "Mirroring aktif • " + config.width + " × " + config.height;
                        updateStatus();
                    }
                    outputIndex = decoder.dequeueOutputBuffer(outputInfo, 0);
                }
            }
        } catch (Exception error) {
            android.util.Log.e("OfflineMirror", "Viewer video receive failed", error);
            if (running) {
                videoStatus = "Gagal menerima video: " + error.getMessage();
                updateStatus();
            }
        } finally {
            running = false;
            try { if (input != null) input.close(); } catch (Exception ignored) {}
            try { if (videoSocket != null) videoSocket.close(); } catch (Exception ignored) {}
            releaseDecoder(decoder);
            closeAuxiliaryConnections();
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

    private StreamConfig readStreamHeader(DataInputStream input) throws Exception {
        int magic = input.readInt();
        if (magic != MAGIC) throw new IllegalStateException("Unsupported stream header");
        int width = input.readInt();
        int height = input.readInt();
        if (width < 2 || height < 2 || width > 4096 || height > 4096) {
            throw new IllegalStateException("Invalid video dimensions");
        }
        byte[] csd0 = readConfig(input);
        byte[] csd1 = readConfig(input);
        // Some hardware encoders send SPS/PPS as a separate codec-config buffer.
        // The decoder accepts that buffer after it has been configured.
        return new StreamConfig(width, height, csd0, csd1);
    }

    private MediaCodec createDecoder(StreamConfig config, Surface surface) throws Exception {
        MediaFormat format = MediaFormat.createVideoFormat(
                MediaFormat.MIMETYPE_VIDEO_AVC, config.width, config.height);
        if (config.csd0.length > 0) format.setByteBuffer("csd-0", ByteBuffer.wrap(config.csd0));
        if (config.csd1.length > 0) format.setByteBuffer("csd-1", ByteBuffer.wrap(config.csd1));
        MediaCodec decoder = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC);
        if (surface == null || !surface.isValid()) {
            throw new IllegalStateException("Layar Viewer belum siap");
        }
        decoder.configure(format, surface, null, 0);
        decoder.start();
        return decoder;
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
            throw new IllegalStateException("Invalid codec configuration");
        }
        byte[] bytes = new byte[size];
        input.readFully(bytes);
        return bytes;
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
        running = false;
        try { if (videoSocket != null) videoSocket.close(); } catch (Exception ignored) {}
        closeAuxiliaryConnections();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) enableImmersiveViewer();
    }

    @Override
    protected void onDestroy() {
        stopReceiving();
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
