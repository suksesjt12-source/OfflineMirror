package com.offlinemirror.app;

import android.app.Activity;
import android.graphics.Color;
import android.media.MediaCodec;
import android.media.MediaFormat;
import android.os.Bundle;
import android.view.Gravity;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;

public class ViewerActivity extends Activity implements SurfaceHolder.Callback {
    private static final int PORT = 48888;
    private static final int MAGIC = 0x4F4D4952;

    private SurfaceView surfaceView;
    private TextView status;
    private String host;
    private volatile boolean running = false;
    private Socket socket;
    private Thread receiveThread;

    private int dp(float value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        host = getIntent().getStringExtra("host");
        getWindow().setStatusBarColor(Color.BLACK);
        getWindow().setNavigationBarColor(Color.BLACK);
        enableImmersiveViewer();

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.BLACK);

        status = new TextView(this);
        status.setText("Connecting to " + host + ":" + PORT + " …");
        status.setTextColor(Color.WHITE);
        status.setTextSize(13);
        status.setGravity(Gravity.CENTER_VERTICAL);
        status.setPadding(dp(14), dp(6), dp(14), dp(6));
        root.addView(status, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(38)));

        surfaceView = new SurfaceView(this);
        surfaceView.setBackgroundColor(Color.BLACK);
        root.addView(surfaceView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        surfaceView.getHolder().addCallback(this);
        setContentView(root);
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
            socket = new Socket();
            socket.setTcpNoDelay(true);
            socket.connect(new InetSocketAddress(host, PORT), 10_000);
            input = new DataInputStream(new BufferedInputStream(socket.getInputStream(), 256 * 1024));

            StreamConfig config = readStreamHeader(input);
            decoder = createDecoder(config, surface);
            updateStatus("Connected • " + config.width + " × " + config.height + " • H.264");

            MediaCodec.BufferInfo outputInfo = new MediaCodec.BufferInfo();
            while (running) {
                int size = input.readInt();

                // The host sends -1 before a new codec header when its screen rotates.
                if (size == -1) {
                    decoder = releaseDecoder(decoder);
                    config = readStreamHeader(input);
                    decoder = createDecoder(config, surface);
                    updateStatus("Orientation updated • " + config.width + " × " + config.height);
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
                int decoderFlags = flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM;
                decoder.queueInputBuffer(inputIndex, 0, packet.length,
                        presentationTimeUs, decoderFlags);

                int outputIndex;
                do {
                    outputIndex = decoder.dequeueOutputBuffer(outputInfo, 0);
                    if (outputIndex >= 0) {
                        decoder.releaseOutputBuffer(outputIndex, true);
                    }
                } while (outputIndex >= 0 && running);
            }
        } catch (Exception error) {
            if (running) updateStatus("Connection ended: " + error.getMessage());
        } finally {
            running = false;
            try { if (input != null) input.close(); } catch (Exception ignored) {}
            try { if (socket != null) socket.close(); } catch (Exception ignored) {}
            releaseDecoder(decoder);
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
        return new StreamConfig(width, height, csd0, csd1);
    }

    private MediaCodec createDecoder(StreamConfig config, Surface surface) throws Exception {
        MediaFormat format = MediaFormat.createVideoFormat(
                MediaFormat.MIMETYPE_VIDEO_AVC, config.width, config.height);
        if (config.csd0.length > 0) {
            format.setByteBuffer("csd-0", ByteBuffer.wrap(config.csd0));
        }
        if (config.csd1.length > 0) {
            format.setByteBuffer("csd-1", ByteBuffer.wrap(config.csd1));
        }
        MediaCodec decoder = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC);
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

    private void updateStatus(String message) {
        runOnUiThread(() -> {
            if (status != null) status.setText(message);
        });
    }

    private void stopReceiving() {
        running = false;
        try { if (socket != null) socket.close(); } catch (Exception ignored) {}
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
