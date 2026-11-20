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
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
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

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class MirrorHostService extends Service {
    private static final String CHANNEL_ID = "offline_mirror_stream";
    private static final int NOTIFICATION_ID = 71;
    private static final int VIDEO_PORT = 48888;
    private static final int AUDIO_PORT = 48889;
    private static final int CONTROL_PORT = 48890;
    private static final int MAGIC = 0x4F4D4952;

    private volatile boolean running = false;
    private boolean audioEnabled = false;
    private String sessionKey = "";

    private ServerSocket videoServerSocket;
    private ServerSocket audioServerSocket;
    private ServerSocket controlServerSocket;
    private Socket videoClientSocket;
    private Socket audioClientSocket;
    private Socket controlClientSocket;

    private MediaProjection projection;
    private MediaProjection.Callback projectionCallback;
    private Thread videoThread;
    private Thread audioThread;
    private Thread controlThread;
    private AudioRecord audioRecord;

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
        audioEnabled = intent.getBooleanExtra("audioEnabled", false);
        sessionKey = intent.getStringExtra("sessionKey");
        if (sessionKey == null || sessionKey.length() < 16 || sessionKey.length() > 128
                || !sessionKey.matches("[A-Za-z0-9_-]+")) {
            android.util.Log.e("OfflineMirror", "Missing or invalid Host pairing key");
            stopSelf();
            return START_NOT_STICKY;
        }
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

            // Prepare the local listening ports before the Viewer connects.
            videoServerSocket = openServerSocket(VIDEO_PORT);
            controlServerSocket = openServerSocket(CONTROL_PORT);
            if (audioEnabled && Build.VERSION.SDK_INT >= 29) {
                audioServerSocket = openServerSocket(AUDIO_PORT);
            }

            int maxDimension = intent.getIntExtra("maxDimension", 1280);
            int fps = intent.getIntExtra("fps", 30);
            int bitrate = intent.getIntExtra("bitrate", 4_000_000);

            videoThread = new Thread(() -> streamVideo(maxDimension, fps, bitrate),
                    "OfflineMirror-Video");
            controlThread = new Thread(this::serveControl, "OfflineMirror-Control");
            videoThread.start();
            controlThread.start();

            if (audioEnabled && Build.VERSION.SDK_INT >= 29) {
                audioThread = new Thread(this::serveAudio, "OfflineMirror-Audio");
                audioThread.start();
            }
        } catch (Exception error) {
            android.util.Log.e("OfflineMirror", "Could not start Host", error);
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

    private void streamVideo(int maxDimension, int fps, int bitrate) {
        EncoderSession session = null;
        DataOutputStream output = null;
        boolean streamHeaderSent = false;

        try {
            videoClientSocket = acceptAuthenticated(videoServerSocket, "video");
            videoClientSocket.setTcpNoDelay(true);
            output = new DataOutputStream(new BufferedOutputStream(
                    videoClientSocket.getOutputStream(), 256 * 1024));

            session = createInitialSession(maxDimension, fps, bitrate);
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();

            long framesSent = 0;
            long bytesSent = 0;
            android.util.Log.i("OfflineMirror", "Single H.264 capture session active at "
                    + session.width + "x" + session.height
                    + ". Rotation changes only resize the Viewer surface.");

            while (running && videoClientSocket != null && !videoClientSocket.isClosed()) {
                // Keep the initial MediaProjection VirtualDisplay and encoder alive.
                // Reconfiguring either during device rotation caused black video on some phones.
                int index = session.encoder.dequeueOutputBuffer(info, 150_000);
                if (index == MediaCodec.INFO_TRY_AGAIN_LATER) continue;

                if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    MediaFormat outputFormat = session.encoder.getOutputFormat();
                    byte[] csd0 = copyBuffer(outputFormat.getByteBuffer("csd-0"));
                    byte[] csd1 = copyBuffer(outputFormat.getByteBuffer("csd-1"));
                    android.util.Log.i("OfflineMirror", "AVC output format: "
                            + session.width + "x" + session.height + " csd0="
                            + csd0.length + " csd1=" + csd1.length);
                    if (streamHeaderSent) output.writeInt(-1);
                    writeStreamHeader(output, session.width, session.height, csd0, csd1);
                    streamHeaderSent = true;
                    continue;
                }
                if (index < 0) continue;

                try {
                    if (streamHeaderSent && info.size > 0) {
                        ByteBuffer encoded = session.encoder.getOutputBuffer(index);
                        if (encoded != null) {
                            encoded.position(info.offset);
                            encoded.limit(info.offset + info.size);
                            byte[] packet = new byte[info.size];
                            encoded.get(packet);
                            output.writeInt(packet.length);
                            int packetFlags = info.flags & (MediaCodec.BUFFER_FLAG_CODEC_CONFIG
                                    | MediaCodec.BUFFER_FLAG_KEY_FRAME
                                    | MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            output.writeInt(packetFlags);
                            output.writeLong(info.presentationTimeUs);
                            output.write(packet);
                            output.flush();
                            framesSent++;
                            bytesSent += packet.length;
                            if (framesSent == 1 || framesSent % 120 == 0) {
                                android.util.Log.i("OfflineMirror", "H.264 frames sent="
                                        + framesSent + " bytes=" + bytesSent
                                        + " resolution=" + session.width + "x" + session.height);
                            }
                        }
                    }
                    if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) break;
                } finally {
                    session.encoder.releaseOutputBuffer(index, false);
                }
            }
        } catch (Exception error) {
            if (running) android.util.Log.e("OfflineMirror",
                    "H.264 video stream stopped: " + error.getMessage(), error);
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

    private void serveAudio() {
        DataOutputStream output = null;
        try {
            audioClientSocket = acceptAuthenticated(audioServerSocket, "audio");
            audioClientSocket.setTcpNoDelay(true);
            output = new DataOutputStream(new BufferedOutputStream(
                    audioClientSocket.getOutputStream(), 32 * 1024));

            final int sampleRate = 44100;
            final int channelMask = AudioFormat.CHANNEL_IN_STEREO;
            int minBytes = AudioRecord.getMinBufferSize(
                    sampleRate, channelMask, AudioFormat.ENCODING_PCM_16BIT);
            int bufferBytes = Math.max(16384, minBytes > 0 ? minBytes * 2 : 32768);

            AudioPlaybackCaptureConfigurationCompat config =
                    new AudioPlaybackCaptureConfigurationCompat(projection);
            AudioFormat format = new AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(channelMask)
                    .build();

            audioRecord = new AudioRecord.Builder()
                    .setAudioFormat(format)
                    .setBufferSizeInBytes(bufferBytes)
                    .setAudioPlaybackCaptureConfig(config.build())
                    .build();

            if (audioRecord.getState() != AudioRecord.STATE_INITIALIZED) {
                throw new IllegalStateException("Playback capture could not initialize");
            }

            output.writeInt(sampleRate);
            output.writeInt(2);
            output.writeInt(AudioFormat.ENCODING_PCM_16BIT);
            output.flush();

            byte[] buffer = new byte[8192];
            audioRecord.startRecording();
            while (running && audioClientSocket != null && !audioClientSocket.isClosed()) {
                int count = audioRecord.read(buffer, 0, buffer.length, AudioRecord.READ_BLOCKING);
                if (count > 0) {
                    output.writeInt(count);
                    output.write(buffer, 0, count);
                    output.flush();
                }
            }
        } catch (Exception error) {
            if (running) android.util.Log.w("OfflineMirror", "Audio capture unavailable: " + error.getMessage());
        } finally {
            if (audioRecord != null) {
                try { audioRecord.stop(); } catch (Exception ignored) {}
                try { audioRecord.release(); } catch (Exception ignored) {}
                audioRecord = null;
            }
            try { if (output != null) output.close(); } catch (Exception ignored) {}
            try { if (audioClientSocket != null) audioClientSocket.close(); } catch (Exception ignored) {}
        }
    }

    private void serveControl() {
        try {
            controlClientSocket = acceptAuthenticated(controlServerSocket, "control");
            controlClientSocket.setTcpNoDelay(true);

            DataInputStream input = new DataInputStream(
                    new BufferedInputStream(controlClientSocket.getInputStream()));

            while (running && !controlClientSocket.isClosed()) {
                int type = input.readUnsignedByte();
                if (type == 1 || type == 2) {
                    float x = input.readFloat();
                    float y = input.readFloat();
                    int duration = input.readInt();
                    RemoteAccessibilityService.dispatchRemoteGesture(
                            type, x, y, x, y, duration);
                } else if (type == 3) {
                    float x1 = input.readFloat();
                    float y1 = input.readFloat();
                    float x2 = input.readFloat();
                    float y2 = input.readFloat();
                    int duration = input.readInt();
                    RemoteAccessibilityService.dispatchRemoteGesture(
                            type, x1, y1, x2, y2, duration);
                } else {
                    throw new IllegalArgumentException("Unknown remote touch command");
                }
            }
        } catch (Exception error) {
            if (running) android.util.Log.w("OfflineMirror", "Remote touch disconnected: " + error.getMessage());
        } finally {
            try { if (controlClientSocket != null) controlClientSocket.close(); } catch (Exception ignored) {}
        }
    }

    private Socket acceptAuthenticated(ServerSocket server, String channel) throws IOException {
        while (running) {
            Socket candidate = server.accept();
            boolean accepted = false;
            try {
                candidate.setSoTimeout(7000);
                DataInputStream authInput = new DataInputStream(candidate.getInputStream());
                String suppliedKey = authInput.readUTF();
                if (sessionKey.equals(suppliedKey)) {
                    candidate.setSoTimeout(0);
                    accepted = true;
                    android.util.Log.i("OfflineMirror", "Authenticated " + channel + " connection");
                    return candidate;
                }
                android.util.Log.w("OfflineMirror", "Rejected " + channel + " connection: key mismatch");
            } catch (Exception error) {
                android.util.Log.w("OfflineMirror", "Rejected " + channel
                        + " connection: " + error.getMessage());
            } finally {
                if (!accepted) {
                    try { candidate.close(); } catch (Exception ignored) {}
                }
            }
        }
        throw new IOException("Host sharing stopped before " + channel + " pairing completed");
    }

    private ServerSocket openServerSocket(int port) throws IOException {
        ServerSocket server = new ServerSocket();
        server.setReuseAddress(true);
        server.bind(new InetSocketAddress(port), 1);
        return server;
    }

    private EncoderSession createInitialSession(int maxDimension, int fps, int bitrate)
            throws Exception {
        DisplayMetrics metrics = new DisplayMetrics();
        WindowManager wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        Display display = wm.getDefaultDisplay();
        display.getRealMetrics(metrics);
        int[] size = scaledSize(metrics, maxDimension);

        MediaCodec encoder = createEncoder(size[0], size[1], fps, bitrate);
        android.util.Log.i("OfflineMirror", "Starting screen capture encoder at "
                + size[0] + "x" + size[1]);
        Surface surface = null;
        VirtualDisplay virtualDisplay = null;
        try {
            // The encoder must be running before VirtualDisplay starts feeding its input Surface.
            surface = encoder.createInputSurface();
            encoder.start();
            requestSyncFrame(encoder);

            virtualDisplay = projection.createVirtualDisplay(
                    "OfflineMirror", size[0], size[1], metrics.densityDpi,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                    surface, null, null);
            if (virtualDisplay == null) {
                throw new IllegalStateException("Unable to create virtual display");
            }
            // Request an IDR frame only after the VirtualDisplay is feeding the encoder.
            requestSyncFrame(encoder);

            return new EncoderSession(encoder, virtualDisplay, surface,
                    size[0], size[1], display.getRotation());
        } catch (Exception error) {
            if (virtualDisplay != null) {
                try { virtualDisplay.release(); } catch (Exception ignored) {}
            }
            if (surface != null) {
                try { surface.release(); } catch (Exception ignored) {}
            }
            try { encoder.stop(); } catch (Exception ignored) {}
            try { encoder.release(); } catch (Exception ignored) {}
            throw error;
        }
    }

    private EncoderSession reconfigureSession(EncoderSession old, int maxDimension,
                                               int fps, int bitrate) throws Exception {
        DisplayMetrics metrics = new DisplayMetrics();
        WindowManager wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        Display display = wm.getDefaultDisplay();
        display.getRealMetrics(metrics);
        int[] size = scaledSize(metrics, maxDimension);

        MediaCodec nextEncoder = createEncoder(size[0], size[1], fps, bitrate);
        Surface nextSurface = null;
        try {
            nextSurface = nextEncoder.createInputSurface();
            nextEncoder.start();

            old.virtualDisplay.setSurface(null);
            old.releaseEncoderOnly();
            old.virtualDisplay.resize(size[0], size[1], metrics.densityDpi);
            old.virtualDisplay.setSurface(nextSurface);
            requestSyncFrame(nextEncoder);

            return new EncoderSession(nextEncoder, old.virtualDisplay, nextSurface,
                    size[0], size[1], display.getRotation());
        } catch (Exception error) {
            if (nextSurface != null) {
                try { nextSurface.release(); } catch (Exception ignored) {}
            }
            try { nextEncoder.stop(); } catch (Exception ignored) {}
            try { nextEncoder.release(); } catch (Exception ignored) {}
            throw error;
        }
    }

    private void requestSyncFrame(MediaCodec encoder) {
        if (Build.VERSION.SDK_INT >= 19) {
            try {
                android.os.Bundle parameters = new android.os.Bundle();
                parameters.putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0);
                encoder.setParameters(parameters);
            } catch (Exception error) {
                android.util.Log.w("OfflineMirror", "Encoder did not accept sync-frame request", error);
            }
        }
    }

    private MediaCodec createEncoder(int width, int height, int fps, int bitrate)
            throws Exception {
        MediaFormat format = MediaFormat.createVideoFormat(
                MediaFormat.MIMETYPE_VIDEO_AVC, width, height);
        format.setInteger(MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
        format.setInteger(MediaFormat.KEY_BIT_RATE, Math.max(500_000, bitrate));
        format.setInteger(MediaFormat.KEY_FRAME_RATE, Math.max(10, Math.min(60, fps)));
        format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1);

        int targetFps = Math.max(10, Math.min(60, fps));
        List<MediaCodecInfo> candidates = new ArrayList<>();
        MediaCodecInfo[] all = new MediaCodecList(MediaCodecList.ALL_CODECS).getCodecInfos();
        for (MediaCodecInfo info : all) {
            if (!info.isEncoder()) continue;
            for (String type : info.getSupportedTypes()) {
                if (!MediaFormat.MIMETYPE_VIDEO_AVC.equalsIgnoreCase(type)) continue;
                try {
                    MediaCodecInfo.CodecCapabilities caps = info.getCapabilitiesForType(type);
                    boolean surfaceInput = false;
                    for (int color : caps.colorFormats) {
                        if (color == MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface) {
                            surfaceInput = true;
                            break;
                        }
                    }
                    if (!surfaceInput) continue;
                    MediaCodecInfo.VideoCapabilities videoCaps = caps.getVideoCapabilities();
                    if (videoCaps != null
                            && !videoCaps.areSizeAndRateSupported(width, height, targetFps)) {
                        continue;
                    }
                    candidates.add(info);
                } catch (Exception error) {
                    android.util.Log.w("OfflineMirror", "Skipping AVC encoder " + info.getName(), error);
                }
                break;
            }
        }

        // Prefer a hardware encoder; retry other compatible AVC encoders if configuration fails.
        Collections.sort(candidates, (left, right) ->
                Boolean.compare(isSoftwareEncoder(left), isSoftwareEncoder(right)));

        Exception lastError = null;
        for (MediaCodecInfo info : candidates) {
            MediaCodec encoder = null;
            try {
                encoder = MediaCodec.createByCodecName(info.getName());
                encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
                android.util.Log.i("OfflineMirror", "Selected AVC encoder: " + info.getName());
                return encoder;
            } catch (Exception error) {
                lastError = error;
                android.util.Log.w("OfflineMirror",
                        "AVC encoder failed, trying next: " + info.getName(), error);
                if (encoder != null) {
                    try { encoder.release(); } catch (Exception ignored) {}
                }
            }
        }

        // A few OEM codec registries omit capabilities that their default encoder still accepts.
        try {
            MediaCodec fallback = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC);
            fallback.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            android.util.Log.i("OfflineMirror", "Using default AVC encoder fallback");
            return fallback;
        } catch (Exception error) {
            lastError = error;
        }

        throw new IllegalStateException(
                "Tidak ada encoder H.264 Surface yang cocok untuk resolusi ini", lastError);
    }

    private boolean isSoftwareEncoder(MediaCodecInfo info) {
        if (Build.VERSION.SDK_INT >= 29) return !info.isHardwareAccelerated();
        String name = info.getName().toLowerCase(java.util.Locale.ROOT);
        return name.contains("google") || name.contains("android") || name.contains("software");
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
        try { if (videoClientSocket != null) videoClientSocket.close(); } catch (Exception ignored) {}
        try { if (videoServerSocket != null) videoServerSocket.close(); } catch (Exception ignored) {}
        try { if (audioClientSocket != null) audioClientSocket.close(); } catch (Exception ignored) {}
        try { if (audioServerSocket != null) audioServerSocket.close(); } catch (Exception ignored) {}
        try { if (controlClientSocket != null) controlClientSocket.close(); } catch (Exception ignored) {}
        try { if (controlServerSocket != null) controlServerSocket.close(); } catch (Exception ignored) {}
    }

    private void stopStreaming() {
        running = false;
        sessionKey = "";
        closeSockets();
        if (audioRecord != null) {
            try { audioRecord.stop(); } catch (Exception ignored) {}
        }
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
                .setContentText("Screen, optional audio and paired remote touch • local network")
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

    private static final class AudioPlaybackCaptureConfigurationCompat {
        private final MediaProjection projection;

        AudioPlaybackCaptureConfigurationCompat(MediaProjection projection) {
            this.projection = projection;
        }

        android.media.AudioPlaybackCaptureConfiguration build() {
            return new android.media.AudioPlaybackCaptureConfiguration.Builder(projection)
                    .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                    .addMatchingUsage(AudioAttributes.USAGE_GAME)
                    .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                    .build();
        }
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
