package com.offlinemirror.app;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.Image;
import android.graphics.ImageFormat;
import android.media.ImageReader;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Size;
import android.view.Gravity;
import android.view.Surface;
import android.view.TextureView;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.BinaryBitmap;
import com.google.zxing.DecodeHintType;
import com.google.zxing.MultiFormatReader;
import com.google.zxing.PlanarYUVLuminanceSource;
import com.google.zxing.Result;
import com.google.zxing.common.HybridBinarizer;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

public class QrScannerActivity extends Activity implements TextureView.SurfaceTextureListener {
    private TextureView preview;
    private TextView status;
    private CameraDevice cameraDevice;
    private CameraCaptureSession captureSession;
    private ImageReader imageReader;
    private Surface previewSurface;
    private HandlerThread cameraThread;
    private Handler cameraHandler;
    private String cameraId;
    private Size imageSize;
    private final AtomicBoolean decoding = new AtomicBoolean(false);
    private volatile boolean resultReturned = false;

    private int dp(float value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(Color.BLACK);
        getWindow().setNavigationBarColor(Color.BLACK);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.BLACK);

        TextView title = new TextView(this);
        title.setText("Scan QR OfflineMirror");
        title.setTextColor(Color.WHITE);
        title.setTextSize(19);
        title.setGravity(Gravity.CENTER);
        title.setPadding(dp(12), dp(16), dp(12), dp(10));
        root.addView(title, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        preview = new TextureView(this);
        preview.setSurfaceTextureListener(this);
        root.addView(preview, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        status = new TextView(this);
        status.setText("Arahkan kamera ke QR yang tampil di HP Host.");
        status.setTextColor(Color.WHITE);
        status.setTextSize(14);
        status.setGravity(Gravity.CENTER);
        status.setPadding(dp(16), dp(12), dp(16), dp(12));
        root.addView(status, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        Button cancel = new Button(this);
        cancel.setText("BATAL");
        cancel.setOnClickListener(v -> {
            setResult(RESULT_CANCELED);
            finish();
        });
        root.addView(cancel, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        setContentView(root);
    }

    @Override
    protected void onResume() {
        super.onResume();
        startCameraThread();
        if (preview.isAvailable()) openCamera();
    }

    @Override
    protected void onPause() {
        closeCamera();
        stopCameraThread();
        super.onPause();
    }

    private void startCameraThread() {
        if (cameraThread != null) return;
        cameraThread = new HandlerThread("OfflineMirror-QR-Camera");
        cameraThread.start();
        cameraHandler = new Handler(cameraThread.getLooper());
    }

    private void stopCameraThread() {
        if (cameraThread == null) return;
        cameraThread.quitSafely();
        try {
            cameraThread.join(1200);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
        }
        cameraThread = null;
        cameraHandler = null;
    }

    private void openCamera() {
        if (cameraDevice != null || isFinishing()) return;
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            updateStatus("Izin kamera belum diberikan. Kembali lalu izinkan kamera.");
            return;
        }
        try {
            CameraManager manager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
            String[] ids = manager.getCameraIdList();
            CameraCharacteristics selected = null;
            for (String id : ids) {
                CameraCharacteristics characteristics = manager.getCameraCharacteristics(id);
                Integer facing = characteristics.get(CameraCharacteristics.LENS_FACING);
                if (facing != null && facing == CameraCharacteristics.LENS_FACING_BACK) {
                    cameraId = id;
                    selected = characteristics;
                    break;
                }
                if (selected == null) {
                    cameraId = id;
                    selected = characteristics;
                }
            }
            if (selected == null || cameraId == null) {
                updateStatus("Kamera tidak ditemukan pada perangkat ini.");
                return;
            }

            StreamConfigurationMap map = selected.get(
                    CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            if (map == null) throw new IllegalStateException("Konfigurasi kamera tidak tersedia");
            imageSize = chooseImageSize(map.getOutputSizes(ImageFormat.YUV_420_888));
            if (imageSize == null) throw new IllegalStateException("Format kamera YUV tidak didukung");

            SurfaceTexture texture = preview.getSurfaceTexture();
            if (texture == null) return;
            texture.setDefaultBufferSize(imageSize.getWidth(), imageSize.getHeight());

            imageReader = ImageReader.newInstance(
                    imageSize.getWidth(), imageSize.getHeight(),
                    ImageFormat.YUV_420_888, 2);
            imageReader.setOnImageAvailableListener(this::decodeFrame, cameraHandler);
            previewSurface = new Surface(texture);

            manager.openCamera(cameraId, new CameraDevice.StateCallback() {
                @Override
                public void onOpened(CameraDevice camera) {
                    cameraDevice = camera;
                    createPreviewSession();
                }

                @Override
                public void onDisconnected(CameraDevice camera) {
                    camera.close();
                    cameraDevice = null;
                    updateStatus("Kamera terputus.");
                }

                @Override
                public void onError(CameraDevice camera, int error) {
                    camera.close();
                    cameraDevice = null;
                    updateStatus("Kamera gagal dibuka (kode " + error + ").");
                }
            }, cameraHandler);
            updateStatus("Membuka kamera...");
        } catch (SecurityException error) {
            updateStatus("Izin kamera ditolak Android.");
        } catch (Exception error) {
            android.util.Log.e("OfflineMirror", "QR camera open failed", error);
            updateStatus("Kamera tidak dapat dibuka: " + error.getMessage());
        }
    }

    private Size chooseImageSize(Size[] sizes) {
        if (sizes == null || sizes.length == 0) return null;
        Size best = null;
        long bestArea = 0;
        for (Size size : sizes) {
            long area = (long) size.getWidth() * size.getHeight();
            if (Math.max(size.getWidth(), size.getHeight()) <= 1600 && area > bestArea) {
                best = size;
                bestArea = area;
            }
        }
        return best != null ? best : sizes[0];
    }

    private void createPreviewSession() {
        if (cameraDevice == null || imageReader == null || previewSurface == null) return;
        try {
            CaptureRequest.Builder request = cameraDevice.createCaptureRequest(
                    CameraDevice.TEMPLATE_PREVIEW);
            request.addTarget(previewSurface);
            request.addTarget(imageReader.getSurface());
            request.set(CaptureRequest.CONTROL_AF_MODE,
                    CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
            request.set(CaptureRequest.CONTROL_AE_MODE,
                    CaptureRequest.CONTROL_AE_MODE_ON);

            cameraDevice.createCaptureSession(Arrays.asList(previewSurface, imageReader.getSurface()),
                    new CameraCaptureSession.StateCallback() {
                        @Override
                        public void onConfigured(CameraCaptureSession session) {
                            if (cameraDevice == null || isFinishing()) {
                                session.close();
                                return;
                            }
                            captureSession = session;
                            try {
                                session.setRepeatingRequest(request.build(), null, cameraHandler);
                                updateStatus("Arahkan kamera ke QR OfflineMirror dari HP Host.");
                            } catch (CameraAccessException error) {
                                updateStatus("Gagal memulai preview kamera.");
                                android.util.Log.e("OfflineMirror", "QR preview failed", error);
                            }
                        }

                        @Override
                        public void onConfigureFailed(CameraCaptureSession session) {
                            updateStatus("Konfigurasi kamera gagal pada perangkat ini.");
                            session.close();
                        }
                    }, cameraHandler);
        } catch (Exception error) {
            android.util.Log.e("OfflineMirror", "QR preview session failed", error);
            updateStatus("Preview kamera gagal: " + error.getMessage());
        }
    }

    private void decodeFrame(ImageReader reader) {
        if (resultReturned) {
            Image stale = reader.acquireLatestImage();
            if (stale != null) stale.close();
            return;
        }
        if (!decoding.compareAndSet(false, true)) {
            Image stale = reader.acquireLatestImage();
            if (stale != null) stale.close();
            return;
        }
        Image image = null;
        try {
            image = reader.acquireLatestImage();
            if (image == null) return;
            Image.Plane[] planes = image.getPlanes();
            if (planes == null || planes.length == 0) return;

            int width = image.getWidth();
            int height = image.getHeight();
            ByteBuffer yBuffer = planes[0].getBuffer();
            int rowStride = planes[0].getRowStride();
            int pixelStride = planes[0].getPixelStride();
            int base = yBuffer.position();
            byte[] luma = new byte[width * height];
            for (int row = 0; row < height; row++) {
                int rowStart = base + row * rowStride;
                int dst = row * width;
                for (int col = 0; col < width; col++) {
                    luma[dst + col] = yBuffer.get(rowStart + col * pixelStride);
                }
            }
            image.close();
            image = null;

            String value = decodeQr(luma, width, height);
            if (value != null && !resultReturned) {
                resultReturned = true;
                runOnUiThread(() -> {
                    Intent data = new Intent();
                    data.putExtra("qr_result", value);
                    setResult(RESULT_OK, data);
                    finish();
                });
            }
        } catch (Exception error) {
            android.util.Log.w("OfflineMirror", "QR frame decode failed", error);
        } finally {
            if (image != null) image.close();
            decoding.set(false);
        }
    }

    private String decodeQr(byte[] data, int width, int height) {
        Map<DecodeHintType, Object> hints = new EnumMap<>(DecodeHintType.class);
        hints.put(DecodeHintType.POSSIBLE_FORMATS, Collections.singletonList(BarcodeFormat.QR_CODE));
        hints.put(DecodeHintType.TRY_HARDER, Boolean.TRUE);
        MultiFormatReader reader = new MultiFormatReader();
        reader.setHints(hints);
        for (int rotation : new int[]{0, 90, 180, 270}) {
            RotatedLuma rotated = rotateLuma(data, width, height, rotation);
            try {
                PlanarYUVLuminanceSource source = new PlanarYUVLuminanceSource(
                        rotated.data, rotated.width, rotated.height,
                        0, 0, rotated.width, rotated.height, false);
                BinaryBitmap bitmap = new BinaryBitmap(new HybridBinarizer(source));
                Result result = reader.decodeWithState(bitmap);
                if (result != null && result.getText() != null) return result.getText();
            } catch (Exception ignored) {
                reader.reset();
            }
        }
        reader.reset();
        return null;
    }

    private RotatedLuma rotateLuma(byte[] source, int width, int height, int degrees) {
        if (degrees == 0) return new RotatedLuma(source, width, height);
        int outWidth = (degrees == 90 || degrees == 270) ? height : width;
        int outHeight = (degrees == 90 || degrees == 270) ? width : height;
        byte[] out = new byte[source.length];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int nx;
                int ny;
                if (degrees == 90) {
                    nx = height - 1 - y;
                    ny = x;
                } else if (degrees == 180) {
                    nx = width - 1 - x;
                    ny = height - 1 - y;
                } else {
                    nx = y;
                    ny = width - 1 - x;
                }
                out[ny * outWidth + nx] = source[y * width + x];
            }
        }
        return new RotatedLuma(out, outWidth, outHeight);
    }

    private void updateStatus(String message) {
        runOnUiThread(() -> {
            if (status != null) status.setText(message);
        });
    }

    private void closeCamera() {
        try {
            if (captureSession != null) {
                captureSession.stopRepeating();
                captureSession.abortCaptures();
                captureSession.close();
            }
        } catch (Exception ignored) {}
        captureSession = null;
        if (cameraDevice != null) {
            try { cameraDevice.close(); } catch (Exception ignored) {}
        }
        cameraDevice = null;
        if (imageReader != null) {
            try { imageReader.close(); } catch (Exception ignored) {}
        }
        imageReader = null;
        if (previewSurface != null) {
            try { previewSurface.release(); } catch (Exception ignored) {}
        }
        previewSurface = null;
    }

    @Override
    public void onSurfaceTextureAvailable(SurfaceTexture surface, int width, int height) {
        openCamera();
    }

    @Override
    public void onSurfaceTextureSizeChanged(SurfaceTexture surface, int width, int height) {
    }

    @Override
    public boolean onSurfaceTextureDestroyed(SurfaceTexture surface) {
        closeCamera();
        return true;
    }

    @Override
    public void onSurfaceTextureUpdated(SurfaceTexture surface) {
    }

    private static final class RotatedLuma {
        final byte[] data;
        final int width;
        final int height;
        RotatedLuma(byte[] data, int width, int height) {
            this.data = data;
            this.width = width;
            this.height = height;
        }
    }
}
