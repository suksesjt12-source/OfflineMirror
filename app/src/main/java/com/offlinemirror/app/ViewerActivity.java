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
        root.addView(surfaceView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        surfaceView.getHolder().addCallback(this);
        setContentView(root);
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

            int magic = input.readInt();
            if (magic != MAGIC) throw new IllegalStateException("Unsupported stream header");
            int width = input.readInt();
            int height = input.readInt();
            if (width < 2 || height < 2 || width > 4096 || height > 4096) {
                throw new IllegalStateException("Invalid video dimensions");
            }

            byte[] csd0 = readConfig(input);
            byte[] csd1 = readConfig(input);
            MediaFormat format = MediaFormat.createVideoFormat(
                    MediaFormat.MIMETYPE_VIDEO_AVC, width, height);
            if (csd0.length > 0) format.setByteBuffer("csd-0", ByteBuffer.wrap(csd0));
            if (csd1.length > 0) format.setByteBuffer("csd-1", ByteBuffer.wrap(csd1));

            decoder = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC);
            decoder.configure(format, surface, null, 0);
            decoder.start();
            updateStatus("Connected • " + width + " × " + height + " • H.264");

            MediaCodec.BufferInfo outputInfo = new MediaCodec.BufferInfo();
            while (running) {
                int size = input.readInt();
                int flags = input.readInt();
                long presentationTimeUs = input.readLong();
                if (size < 0 || size > 8 * 1024 * 1024) {
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
                int decoderFlags = (flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM);
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
            if (decoder != null) {
                try { decoder.stop(); } catch (Exception ignored) {}
                try { decoder.release(); } catch (Exception ignored) {}
            }
        }
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
    protected void onDestroy() {
        stopReceiving();
        super.onDestroy();
    }
}
