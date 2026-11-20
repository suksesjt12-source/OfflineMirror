package com.offlinemirror.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.MediaCodec;
import android.media.MediaFormat;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.util.DisplayMetrics;
import android.view.Display;
import android.view.Surface;
import android.view.WindowManager;

import java.io.BufferedOutputStream;
import java.io.DataOutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.ByteBuffer;

public class MirrorHostService extends Service {
    private static final String CHANNEL_ID = "offline_mirror_stream";
    private static final int NOTIFICATION_ID = 71;
    private static final int PORT = 48888;
    private static final int MAGIC = 0x4F4D4952;

    private volatile boolean running = false;
    private ServerSocket serverSocket;
    private Socket clientSocket;
    private MediaProjection projection;
    private MediaProjection.Callback projectionCallback;
    private Thread streamThread;

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null || "STOP".equals(intent.getAction())) {
            stopStreaming();
            stopSelf();
            return START_NOT_STICKY;
        }
        if (running) return START_NOT_STICKY;

        int resultCode = intent.getIntExtra("resultCode", 0);
        Intent projectionData = getProjectionData(intent);
        if (projectionData == null || resultCode == 0) {
            stopSelf();
            return START_NOT_STICKY;
        }

        Notification notification = buildNotification();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification,
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }

        try {
            MediaProjectionManager manager =
                    (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
            projection = manager.getMediaProjection(resultCode, projectionData);
            if (projection == null) {
                stopSelf();
                return START_NOT_STICKY;
            }
            projectionCallback = new MediaProjection.Callback() {
                @Override
                public void onStop() {
                    running = false;
                    closeSockets();
                    stopSelf();
                }
            };
            projection.registerCallback(projectionCallback, new Handler(getMainLooper()));
            running = true;

            int maxDimension = intent.getIntExtra("maxDimension", 1280);
            int fps = intent.getIntExtra("fps", 30);
            int bitrate = intent.getIntExtra("bitrate", 4_000_000);
            streamThread = new Thread(() -> streamScreen(maxDimension, fps, bitrate),
                    "OfflineMirror-Encoder");
            streamThread.start();
        } catch (Exception error) {
            stopStreaming();
            stopSelf();
        }
        return START_NOT_STICKY;
    }

    @SuppressWarnings("deprecation")
    private Intent getProjectionData(Intent source) {
        if (Build.VERSION.SDK_INT >= 33) {
            return source.getParcelableExtra("projectionData", Intent.class);
        }
        return source.getParcelableExtra("projectionData");
    }

    private void streamScreen(int maxDimension, int fps, int bitrate) {
        EncoderSession session = null;
        DataOutputStream output = null;
        boolean streamHeaderSent = false;

        try {
            serverSocket = new ServerSocket(PORT);
            serverSocket.setReuseAddress(true);
            clientSocket = serverSocket.accept();
            clientSocket.setTcpNoDelay(true);
            output = new DataOutputStream(new BufferedOutputStream(
                    clientSocket.getOutputStream(), 256 * 1024));

            session = createInitialSession(maxDimension, fps, bitrate);
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();

            while (running && clientSocket != null && !clientSocket.isClosed()) {
                int currentRotation = getDisplayRotation();
                if (currentRotation != session.rotation) {
                    // Keep the same VirtualDisplay. Android 14+ limits a MediaProjection
                    // session to one VirtualDisplay; resize it and swap its encoder Surface.
                    session = reconfigureSession(session, maxDimension, fps, bitrate);
                    continue;
                }

                int index = session.encoder.dequeueOutputBuffer(info, 150_000);
                if (index == MediaCodec.INFO_TRY_AGAIN_LATER) continue;

                if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    MediaFormat outputFormat = session.encoder.getOutputFormat();
                    byte[] csd0 = copyBuffer(outputFormat.getByteBuffer("csd-0"));
                    byte[] csd1 = copyBuffer(outputFormat.getByteBuffer("csd-1"));
                    if (streamHeaderSent) output.writeInt(-1);
                    writeStreamHeader(output, session.width, session.height, csd0, csd1);
                    streamHeaderSent = true;
                    continue;
                }
                if (index < 0) continue;

                try {
                    if (streamHeaderSent && info.size > 0 &&
                            (info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                        ByteBuffer encoded = session.encoder.getOutputBuffer(index);
                        if (encoded != null) {
                            encoded.position(info.offset);
                            encoded.limit(info.offset + info.size);
                            byte[] packet = new byte[info.size];
                            encoded.get(packet);
                            output.writeInt(packet.length);
                            output.writeInt(info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            output.writeLong(info.presentationTimeUs);
                            output.write(packet);
                            output.flush();
                        }
                    }
                    if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) break;
                } finally {
                    session.encoder.releaseOutputBuffer(index, false);
                }
            }
        } catch (Exception error) {
            if (running) {
                android.util.Log.e("OfflineMirror", "Stream stopped: " + error.getMessage(), error);
            }
        } finally {
            running = false;
            closeSockets();
            if (session != null) session.release();
            if (output != null) {
                try { output.close(); } catch (Exception ignored) {}
            }
            stopSelf();
        }
    }

    private EncoderSession createInitialSession(int maxDimension, int fps, int bitrate)
            throws Exception {
        DisplayMetrics metrics = new DisplayMetrics();
        WindowManager wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        Display display = wm.getDefaultDisplay();
        display.getRealMetrics(metrics);
        int[] size = scaledSize(metrics, maxDimension);

        MediaCodec encoder = createEncoder(size[0], size[1], fps, bitrate);
        Surface surface = encoder.createInputSurface();
        VirtualDisplay virtualDisplay = projection.createVirtualDisplay(
                "OfflineMirror", size[0], size[1], metrics.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                surface, null, null);
        if (virtualDisplay == null) {
            surface.release();
            encoder.release();
            throw new IllegalStateException("Unable to create virtual display");
        }
        encoder.start();
        return new EncoderSession(encoder, virtualDisplay, surface,
                size[0], size[1], display.getRotation());
    }

    private EncoderSession reconfigureSession(EncoderSession old, int maxDimension,
                                               int fps, int bitrate) throws Exception {
        DisplayMetrics metrics = new DisplayMetrics();
        WindowManager wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        Display display = wm.getDefaultDisplay();
        display.getRealMetrics(metrics);
        int[] size = scaledSize(metrics, maxDimension);

        MediaCodec nextEncoder = createEncoder(size[0], size[1], fps, bitrate);
        Surface nextSurface = nextEncoder.createInputSurface();

        old.virtualDisplay.setSurface(null);
        old.releaseEncoderOnly();
        old.virtualDisplay.resize(size[0], size[1], metrics.densityDpi);
        old.virtualDisplay.setSurface(nextSurface);
        nextEncoder.start();

        return new EncoderSession(nextEncoder, old.virtualDisplay, nextSurface,
                size[0], size[1], display.getRotation());
    }

    private MediaCodec createEncoder(int width, int height, int fps, int bitrate)
            throws Exception {
        MediaFormat format = MediaFormat.createVideoFormat(
                MediaFormat.MIMETYPE_VIDEO_AVC, width, height);
        format.setInteger(MediaFormat.KEY_COLOR_FORMAT, 0x7F000789);
        format.setInteger(MediaFormat.KEY_BIT_RATE, Math.max(500_000, bitrate));
        format.setInteger(MediaFormat.KEY_FRAME_RATE, Math.max(10, Math.min(60, fps)));
        format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1);

        MediaCodec encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC);
        encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        return encoder;
    }

    private int[] scaledSize(DisplayMetrics metrics, int maxDimension) {
        int sourceWidth = Math.max(2, metrics.widthPixels);
        int sourceHeight = Math.max(2, metrics.heightPixels);
        float scale = Math.min(1f, (float) maxDimension /
                (float) Math.max(sourceWidth, sourceHeight));
        return new int[]{even((int) (sourceWidth * scale)),
                even((int) (sourceHeight * scale))};
    }

    private void writeStreamHeader(DataOutputStream output, int width, int height,
                                   byte[] csd0, byte[] csd1) throws Exception {
        output.writeInt(MAGIC);
        output.writeInt(width);
        output.writeInt(height);
        output.writeInt(csd0.length);
        output.write(csd0);
        output.writeInt(csd1.length);
        output.write(csd1);
        output.flush();
    }

    private int getDisplayRotation() {
        WindowManager wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        return wm.getDefaultDisplay().getRotation();
    }

    private int even(int value) {
        int safe = Math.max(2, value);
        return safe - (safe % 2);
    }

    private byte[] copyBuffer(ByteBuffer buffer) {
        if (buffer == null) return new byte[0];
        ByteBuffer copy = buffer.duplicate();
        byte[] bytes = new byte[copy.remaining()];
        copy.get(bytes);
        return bytes;
    }

    private void closeSockets() {
        try { if (clientSocket != null) clientSocket.close(); } catch (Exception ignored) {}
        try { if (serverSocket != null) serverSocket.close(); } catch (Exception ignored) {}
    }

    private void stopStreaming() {
        running = false;
        closeSockets();
        if (projection != null) {
            try {
                if (projectionCallback != null) projection.unregisterCallback(projectionCallback);
                projection.stop();
            } catch (Exception ignored) {}
            projection = null;
        }
        if (Build.VERSION.SDK_INT >= 24) {
            stopForeground(STOP_FOREGROUND_REMOVE);
        } else {
            stopForeground(true);
        }
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID, "OfflineMirror screen sharing",
                    NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("Keeps local screen sharing active");
            NotificationManager manager = getSystemService(NotificationManager.class);
            manager.createNotificationChannel(channel);
        }
    }

    private Notification buildNotification() {
        Intent stopIntent = new Intent(this, MirrorHostService.class);
        stopIntent.setAction("STOP");
        PendingIntent stopPending = PendingIntent.getService(
                this, 72, stopIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Notification.Builder builder;
        if (Build.VERSION.SDK_INT >= 26) {
            builder = new Notification.Builder(this, CHANNEL_ID);
        } else {
            builder = new Notification.Builder(this);
        }
        return builder
                .setContentTitle("OfflineMirror is sharing")
                .setContentText("Local mirroring • auto portrait/landscape • port " + PORT)
                .setSmallIcon(android.R.drawable.ic_menu_camera)
                .setOngoing(true)
                .addAction(android.R.drawable.ic_media_pause, "Stop", stopPending)
                .build();
    }

    @Override
    public void onDestroy() {
        stopStreaming();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private static final class EncoderSession {
        final MediaCodec encoder;
        final VirtualDisplay virtualDisplay;
        final Surface surface;
        final int width;
        final int height;
        final int rotation;

        EncoderSession(MediaCodec encoder, VirtualDisplay virtualDisplay, Surface surface,
                       int width, int height, int rotation) {
            this.encoder = encoder;
            this.virtualDisplay = virtualDisplay;
            this.surface = surface;
            this.width = width;
            this.height = height;
            this.rotation = rotation;
        }

        void releaseEncoderOnly() {
            try { encoder.stop(); } catch (Exception ignored) {}
            try { encoder.release(); } catch (Exception ignored) {}
            try { surface.release(); } catch (Exception ignored) {}
        }

        void release() {
            try { virtualDisplay.setSurface(null); } catch (Exception ignored) {}
            releaseEncoderOnly();
            try { virtualDisplay.release(); } catch (Exception ignored) {}
        }
    }
}
