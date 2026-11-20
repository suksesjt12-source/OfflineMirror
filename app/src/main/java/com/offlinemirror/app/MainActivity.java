package com.offlinemirror.app;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;


import java.util.UUID;
import java.util.HashMap;
import java.util.Map;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;

public class MainActivity extends Activity {
    private static final int REQUEST_CAPTURE = 4101;
    private static final int REQUEST_AUDIO = 4102;
    private static final int REQUEST_CAMERA = 4103;
    private static final int REQUEST_QR = 4104;
    private static final int VIDEO_PORT = 48888;
    private static final int AUDIO_PORT = 48889;
    private static final int CONTROL_PORT = 48890;

    private Spinner resolutionSpinner;
    private Spinner fpsSpinner;
    private Spinner bitrateSpinner;
    private CheckBox adaptiveFpsCheck;
    private CheckBox screenOffModeCheck;
    private EditText addressInput;
    private EditText keyInput;
    private CheckBox audioCheck;
    private TextView hostAddress;
    private TextView hostKeyText;
    private TextView statusText;
    private CheckBox viewerAudioCheck;
    private Button connectButton;
    private Button showQrButton;
    private Button scanQrButton;
    private String sessionKey = "";

    private int dp(float value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(Color.rgb(9, 13, 24));
        getWindow().setNavigationBarColor(Color.rgb(9, 13, 24));
        buildScreen();
    }

    private void buildScreen() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(Color.rgb(9, 13, 24));

        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(dp(22), dp(28), dp(22), dp(28));
        scroll.addView(page);

        TextView title = new TextView(this);
        title.setText("OfflineMirror");
        title.setTextColor(Color.WHITE);
        title.setTextSize(30);
        title.setGravity(Gravity.CENTER);
        title.setTypeface(null, 1);
        page.addView(title, matchWrap());

        TextView subtitle = new TextView(this);
        subtitle.setText("Offline screen mirroring • Portrait • Local Wi-Fi");
        subtitle.setTextColor(Color.rgb(161, 174, 196));
        subtitle.setTextSize(14);
        subtitle.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams subParams = matchWrap();
        subParams.topMargin = dp(6);
        subParams.bottomMargin = dp(24);
        page.addView(subtitle, subParams);

        addSectionTitle(page, "HOST MODE — SHARE THIS PHONE");
        addLabel(page, "Video quality");
        resolutionSpinner = addSpinner(page, new String[]{"480p (hemat bandwidth)", "720p (recommended)", "1080p (higher bandwidth)"});
        addLabel(page, "Batas frame rate maksimum");
        fpsSpinner = addSpinner(page, new String[]{"15 FPS", "24 FPS", "30 FPS", "60 FPS"});
        fpsSpinner.setSelection(2);

        adaptiveFpsCheck = new CheckBox(this);
        adaptiveFpsCheck.setText("Smart Network: FPS dan bitrate adaptif sesuai jaringan/kemampuan HP");
        adaptiveFpsCheck.setTextColor(Color.rgb(196, 207, 224));
        adaptiveFpsCheck.setTextSize(13);
        adaptiveFpsCheck.setChecked(true);
        LinearLayout.LayoutParams adaptiveFpsParams = matchWrap();
        adaptiveFpsParams.topMargin = dp(4);
        page.addView(adaptiveFpsCheck, adaptiveFpsParams);

        addLabel(page, "Bitrate");
        bitrateSpinner = addSpinner(page, new String[]{"2 Mbps", "4 Mbps", "6 Mbps"});
        bitrateSpinner.setSelection(1);

        audioCheck = new CheckBox(this);
        audioCheck.setText("Share device playback audio (Android 10+)");
        audioCheck.setTextColor(Color.rgb(196, 207, 224));
        audioCheck.setTextSize(13);
        audioCheck.setChecked(Build.VERSION.SDK_INT >= 29);
        audioCheck.setEnabled(Build.VERSION.SDK_INT >= 29);
        LinearLayout.LayoutParams audioParams = matchWrap();
        audioParams.topMargin = dp(10);
        page.addView(audioCheck, audioParams);

        screenOffModeCheck = new CheckBox(this);
        screenOffModeCheck.setText("Screen-Off Mirroring (jaga CPU saat layar Host mati)");
        screenOffModeCheck.setTextColor(Color.rgb(196, 207, 224));
        screenOffModeCheck.setTextSize(13);
        screenOffModeCheck.setChecked(true);
        LinearLayout.LayoutParams screenOffParams = matchWrap();
        screenOffParams.topMargin = dp(8);
        page.addView(screenOffModeCheck, screenOffParams);

        TextView screenOffHint = new TextView(this);
        screenOffHint.setText("Setelah Viewer tersambung, tekan tombol Power Host sekali. CPU dijaga aktif hanya saat layar mati. Android tertentu tetap dapat menghentikan screen capture.");
        screenOffHint.setTextColor(Color.rgb(135, 153, 180));
        screenOffHint.setTextSize(12);
        screenOffHint.setLineSpacing(dp(2), 1f);
        LinearLayout.LayoutParams screenOffHintParams = matchWrap();
        screenOffHintParams.leftMargin = dp(4);
        screenOffHintParams.topMargin = dp(2);
        page.addView(screenOffHint, screenOffHintParams);

        Button startButton = makeButton("START SCREEN SHARING", Color.rgb(43, 116, 235));
        LinearLayout.LayoutParams startParams = matchWrap();
        startParams.topMargin = dp(12);
        page.addView(startButton, startParams);
        startButton.setOnClickListener(v -> beginHostFlow());

        hostAddress = new TextView(this);
        hostAddress.setText("Alamat HP ini (IP lokal): " + NetworkAddress.getLocalIpv4(this)
                + "  •  Port Host: " + VIDEO_PORT);
        hostAddress.setTextColor(Color.rgb(190, 205, 229));
        hostAddress.setTextSize(14);
        hostAddress.setPadding(dp(12), dp(14), dp(12), dp(14));
        LinearLayout.LayoutParams ipParams = matchWrap();
        ipParams.topMargin = dp(8);
        page.addView(hostAddress, ipParams);

        hostKeyText = new TextView(this);
        hostKeyText.setText("Pairing key: belum aktif");
        hostKeyText.setTextColor(Color.rgb(190, 205, 229));
        hostKeyText.setTextSize(14);
        hostKeyText.setTextIsSelectable(true);
        hostKeyText.setPadding(dp(12), dp(12), dp(12), dp(12));
        LinearLayout.LayoutParams keyParams = matchWrap();
        keyParams.topMargin = dp(4);
        page.addView(hostKeyText, keyParams);

        Button copyButton = makeButton("COPY HOST ADDRESS", Color.rgb(35, 48, 69));
        LinearLayout.LayoutParams copyParams = matchWrap();
        copyParams.topMargin = dp(8);
        page.addView(copyButton, copyParams);
        copyButton.setOnClickListener(v -> {
            String value = NetworkAddress.getLocalIpv4(this);
            ClipboardManager clipboard = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            clipboard.setPrimaryClip(ClipData.newPlainText("OfflineMirror host", value));
            Toast.makeText(this, "Host address copied", Toast.LENGTH_SHORT).show();
        });

        Button copyKeyButton = makeButton("COPY PAIRING KEY", Color.rgb(35, 48, 69));
        LinearLayout.LayoutParams copyKeyParams = matchWrap();
        copyKeyParams.topMargin = dp(8);
        page.addView(copyKeyButton, copyKeyParams);
        copyKeyButton.setOnClickListener(v -> {
            if (sessionKey.isEmpty()) {
                Toast.makeText(this, "Start sharing terlebih dahulu.", Toast.LENGTH_SHORT).show();
                return;
            }
            ClipboardManager clipboard = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            clipboard.setPrimaryClip(ClipData.newPlainText("OfflineMirror pairing key", sessionKey));
            Toast.makeText(this, "Pairing key disalin. Bagikan hanya ke Viewer yang dipercaya.",
                    Toast.LENGTH_LONG).show();
        });

        showQrButton = makeButton("SHOW PAIRING QR", Color.rgb(43, 83, 132));
        showQrButton.setEnabled(false);
        LinearLayout.LayoutParams showQrParams = matchWrap();
        showQrParams.topMargin = dp(8);
        page.addView(showQrButton, showQrParams);
        showQrButton.setOnClickListener(v -> showHostQr());

        Button remoteControlButton = makeButton(
                "ENABLE REMOTE CONTROL ON THIS HOST", Color.rgb(91, 72, 132));
        LinearLayout.LayoutParams remoteParams = matchWrap();
        remoteParams.topMargin = dp(8);
        page.addView(remoteControlButton, remoteParams);
        remoteControlButton.setOnClickListener(v -> {
            new AlertDialog.Builder(this)
                    .setTitle("Aktifkan Remote Control pada HP Host")
                    .setMessage("Aktifkan layanan ini hanya pada HP yang menjadi Host. Setelah aktif, sentuhan dan tombol gamepad virtual dari Viewer dikirim melalui Wi-Fi lokal. Gamepad ini mensimulasikan sentuhan pada posisi kontrol bawaan, bukan membuat gamepad HID universal; game dengan tata letak berbeda atau yang mewajibkan controller fisik mungkin tidak merespons. Layanan hanya mengirim gesture dan tidak membaca isi layar. Android 13/14 mungkin meminta izin setelan terbatas untuk aplikasi yang dipasang dari APK.")
                    .setNegativeButton("Nanti", null)
                    .setNeutralButton("Info Aplikasi", (dialog, which) -> {
                        Intent details = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                Uri.parse("package:" + getPackageName()));
                        startActivity(details);
                    })
                    .setPositiveButton("Buka Aksesibilitas", (dialog, which) ->
                            startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)))
                    .show();
        });

        Button stopButton = makeButton("STOP SHARING", Color.rgb(151, 54, 66));
        LinearLayout.LayoutParams stopParams = matchWrap();
        stopParams.topMargin = dp(8);
        page.addView(stopButton, stopParams);
        stopButton.setOnClickListener(v -> {
            stopService(new Intent(this, MirrorHostService.class));
            sessionKey = "";
            if (hostKeyText != null) hostKeyText.setText("Pairing key: tidak aktif");
            if (showQrButton != null) showQrButton.setEnabled(false);
            setStatus("Sharing dihentikan. Pairing key sebelumnya sudah tidak berlaku.");
        });

        addDivider(page);
        addSectionTitle(page, "VIEWER MODE — CONNECT TO ANOTHER PHONE");
        addLabel(page, "Host IP address");
        addressInput = new EditText(this);
        styleInput(addressInput, "Example: 192.168.43.1");
        addressInput.setInputType(android.text.InputType.TYPE_CLASS_TEXT |
                android.text.InputType.TYPE_TEXT_VARIATION_URI);
        page.addView(addressInput, matchWrap());

        addLabel(page, "Pairing key dari HP Host");
        keyInput = new EditText(this);
        styleInput(keyInput, "Masukkan pairing key dari HP Host");
        keyInput.setSingleLine(true);
        page.addView(keyInput, matchWrap());

        viewerAudioCheck = new CheckBox(this);
        viewerAudioCheck.setText("Terima audio dari Host (Android 10+)");
        viewerAudioCheck.setTextColor(Color.rgb(196, 207, 224));
        viewerAudioCheck.setTextSize(13);
        viewerAudioCheck.setChecked(Build.VERSION.SDK_INT >= 29);
        viewerAudioCheck.setEnabled(Build.VERSION.SDK_INT >= 29);
        LinearLayout.LayoutParams viewerAudioParams = matchWrap();
        viewerAudioParams.topMargin = dp(8);
        page.addView(viewerAudioCheck, viewerAudioParams);

        connectButton = makeButton("CONNECT TO HOST", Color.rgb(24, 139, 106));
        LinearLayout.LayoutParams connectParams = matchWrap();
        connectParams.topMargin = dp(12);
        page.addView(connectButton, connectParams);
        connectButton.setOnClickListener(v -> connectToHost());

        scanQrButton = makeButton("SCAN HOST QR", Color.rgb(43, 83, 132));
        LinearLayout.LayoutParams scanParams = matchWrap();
        scanParams.topMargin = dp(8);
        page.addView(scanQrButton, scanParams);
        scanQrButton.setOnClickListener(v -> startQrScanner());

        statusText = new TextView(this);
        statusText.setTextColor(Color.rgb(159, 177, 203));
        statusText.setText("Hubungkan kedua HP ke Wi-Fi/hotspot yang sama. Scan QR dari HP Host atau masukkan IP dan pairing key.");
        LinearLayout.LayoutParams statusParams = matchWrap();
        statusParams.topMargin = dp(20);
        page.addView(statusText, statusParams);

        TextView note = new TextView(this);
        note.setText("QR berisi IP, port, dan pairing key sesi Host. Gunakan Wi-Fi/hotspot yang sama; internet tidak diperlukan. Host mendukung maksimal 4 Viewer video. Gamepad virtual menggunakan Bluetooth HID saja (Android 9+ dan dukungan HID Device dari vendor; pair Viewer dan Host melalui Setelan Bluetooth). Wi-Fi dipakai untuk mirroring, tap, hold, swipe, volume, media, dan clipboard. Aktifkan Aksesibilitas Remote Touch pada Host. Kompatibilitas: Android 5.0+ untuk video, Android 7.0+ untuk sentuhan jarak jauh, Android 10+ untuk audio.");
        note.setTextColor(Color.rgb(120, 137, 164));
        note.setTextSize(12);
        note.setLineSpacing(dp(3), 1f);
        LinearLayout.LayoutParams noteParams = matchWrap();
        noteParams.topMargin = dp(18);
        page.addView(note, noteParams);

        setContentView(scroll);
    }

    private void startQrScanner() {
        // Android 5.0–5.1 grants manifest permissions at install time.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                && checkSelfPermission(Manifest.permission.CAMERA)
                        != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.CAMERA}, REQUEST_CAMERA);
            return;
        }
        launchQrScanner();
    }

    private void launchQrScanner() {
        try {
            startActivityForResult(new Intent(this, QrScannerActivity.class), REQUEST_QR);
        } catch (Exception error) {
            Toast.makeText(this, "Pemindai QR tidak dapat dibuka: " + error.getMessage(),
                    Toast.LENGTH_LONG).show();
            android.util.Log.e("OfflineMirror", "Could not launch internal QR scanner", error);
        }
    }

    private boolean applyHostQr(String raw) {
        try {
            Uri payload = Uri.parse(raw);
            if (!"offlinemirror".equalsIgnoreCase(payload.getScheme())
                    || !"connect".equalsIgnoreCase(payload.getHost())) {
                throw new IllegalArgumentException("QR bukan QR koneksi OfflineMirror");
            }
            String host = payload.getQueryParameter("host");
            String portText = payload.getQueryParameter("port");
            String key = payload.getQueryParameter("key");
            int port = Integer.parseInt(portText == null ? "-1" : portText);
            if (!NetworkAddress.isValidIpv4(host) || port != VIDEO_PORT
                    || !isValidSessionKey(key)) {
                throw new IllegalArgumentException("Data Host pada QR tidak valid");
            }
            addressInput.setText(host);
            keyInput.setText(key);
            return true;
        } catch (Exception error) {
            Toast.makeText(this, "QR tidak valid: " + error.getMessage(),
                    Toast.LENGTH_LONG).show();
            android.util.Log.w("OfflineMirror", "Rejected QR payload", error);
            return false;
        }
    }

    private void showHostQr() {
        if (sessionKey.isEmpty()) {
            Toast.makeText(this, "Mulai screen sharing terlebih dahulu.", Toast.LENGTH_SHORT).show();
            return;
        }
        String ip = NetworkAddress.getLocalIpv4(this);
        if (!NetworkAddress.isValidIpv4(ip)) {
            Toast.makeText(this, "IP Wi-Fi belum tersedia. Hubungkan HP ke Wi-Fi/hotspot dahulu.",
                    Toast.LENGTH_LONG).show();
            return;
        }

        String payload = new Uri.Builder()
                .scheme("offlinemirror")
                .authority("connect")
                .appendQueryParameter("host", ip)
                .appendQueryParameter("port", String.valueOf(VIDEO_PORT))
                .appendQueryParameter("key", sessionKey)
                .build().toString();

        try {
            Bitmap qr = createQrBitmap(payload, 640);
            LinearLayout content = new LinearLayout(this);
            content.setOrientation(LinearLayout.VERTICAL);
            content.setGravity(Gravity.CENTER);
            content.setPadding(dp(16), dp(8), dp(16), dp(8));

            ImageView image = new ImageView(this);
            image.setImageBitmap(qr);
            image.setBackgroundColor(Color.WHITE);
            image.setAdjustViewBounds(true);
            content.addView(image, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(300)));

            TextView details = new TextView(this);
            details.setText("Viewer: pilih SCAN HOST QR dan arahkan kamera ke kode ini.\nHost IP: "
                    + ip + "\nPort video: " + VIDEO_PORT);
            details.setTextColor(Color.DKGRAY);
            details.setTextSize(14);
            details.setPadding(0, dp(12), 0, 0);
            content.addView(details, matchWrap());

            new AlertDialog.Builder(this)
                    .setTitle("OfflineMirror — Pairing QR")
                    .setView(content)
                    .setPositiveButton("Tutup", null)
                    .show();
        } catch (Exception error) {
            Toast.makeText(this, "Gagal membuat QR: " + error.getMessage(),
                    Toast.LENGTH_LONG).show();
            android.util.Log.e("OfflineMirror", "Could not generate QR", error);
        }
    }

    private Bitmap createQrBitmap(String value, int size) throws Exception {
        Map<EncodeHintType, Object> hints = new HashMap<>();
        hints.put(EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.M);
        hints.put(EncodeHintType.MARGIN, 2);
        hints.put(EncodeHintType.CHARACTER_SET, "UTF-8");
        BitMatrix matrix = new QRCodeWriter().encode(
                value, BarcodeFormat.QR_CODE, size, size, hints);
        int[] pixels = new int[size * size];
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                pixels[y * size + x] = matrix.get(x, y) ? Color.BLACK : Color.WHITE;
            }
        }
        Bitmap bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        bitmap.setPixels(pixels, 0, size, 0, 0, size, size);
        return bitmap;
    }

    private void beginHostFlow() {
        if (audioCheck.isChecked() && Build.VERSION.SDK_INT >= 29 &&
                checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                        != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQUEST_AUDIO);
            return;
        }
        requestScreenCapture();
    }

    private void requestScreenCapture() {
        MediaProjectionManager manager =
                (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        startActivityForResult(manager.createScreenCaptureIntent(), REQUEST_CAPTURE);
    }

    private void connectToHost() {
        String host = addressInput.getText().toString().trim();
        String key = keyInput.getText().toString().trim();

        if (!NetworkAddress.isValidIpv4(host)) {
            addressInput.setError("Masukkan IP lokal Host yang benar.");
            return;
        }
        if (!isValidSessionKey(key)) {
            keyInput.setError("Masukkan pairing key dari HP Host.");
            return;
        }

        Intent viewer = new Intent(this, ViewerActivity.class);
        viewer.putExtra("host", host);
        viewer.putExtra("sessionKey", key);
        viewer.putExtra("audioEnabled", viewerAudioCheck != null && viewerAudioCheck.isChecked());
        viewer.putExtra("videoPort", VIDEO_PORT);
        viewer.putExtra("audioPort", AUDIO_PORT);
        viewer.putExtra("controlPort", CONTROL_PORT);
        startActivity(viewer);
    }

    private boolean isValidSessionKey(String key) {
        return key != null && key.length() >= 16 && key.length() <= 128
                && key.matches("[A-Za-z0-9_-]+");
    }

    private void styleInput(EditText input, String hint) {
        input.setSingleLine(true);
        input.setTextColor(Color.WHITE);
        input.setHintTextColor(Color.rgb(130, 145, 168));
        input.setHint(hint);
        input.setTextSize(15);
        input.setPadding(dp(14), dp(12), dp(14), dp(12));
        input.setBackgroundTintList(android.content.res.ColorStateList.valueOf(Color.rgb(82, 116, 165)));
    }

    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private void addSectionTitle(LinearLayout parent, String text) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextColor(Color.rgb(115, 174, 255));
        view.setTextSize(13);
        view.setTypeface(null, 1);
        view.setPadding(0, dp(8), 0, dp(10));
        parent.addView(view, matchWrap());
    }

    private void addLabel(LinearLayout parent, String text) {
        TextView label = new TextView(this);
        label.setText(text);
        label.setTextColor(Color.rgb(196, 207, 224));
        label.setTextSize(13);
        LinearLayout.LayoutParams params = matchWrap();
        params.topMargin = dp(8);
        params.bottomMargin = dp(4);
        parent.addView(label, params);
    }

    private Spinner addSpinner(LinearLayout parent, String[] options) {
        Spinner spinner = new Spinner(this);
        ArrayAdapter<String> adapter = new ArrayAdapter<>(
                this, android.R.layout.simple_spinner_item, options);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinner.setAdapter(adapter);
        parent.addView(spinner, matchWrap());
        return spinner;
    }

    private Button makeButton(String text, int color) {
        Button button = new Button(this);
        button.setText(text);
        button.setTextColor(Color.WHITE);
        button.setTextSize(13);
        button.setAllCaps(false);
        button.setBackgroundTintList(android.content.res.ColorStateList.valueOf(color));
        return button;
    }

    private void addDivider(LinearLayout parent) {
        View divider = new View(this);
        divider.setBackgroundColor(Color.rgb(37, 49, 69));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(1));
        params.topMargin = dp(24);
        params.bottomMargin = dp(18);
        parent.addView(divider, params);
    }

    private void setStatus(String message) {
        if (statusText != null) statusText.setText(message);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (hostAddress != null) {
            hostAddress.setText("Alamat HP ini (IP lokal): "
                    + NetworkAddress.getLocalIpv4(this) + "  •  Port Host: " + VIDEO_PORT);
        }
        if (statusText != null) {
            setStatus("Siap. Sambungkan kedua HP ke hotspot yang sama; masukkan IP Host pada Viewer.");
        }
    }

    @Override
    @SuppressWarnings("deprecation")
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode == REQUEST_QR) {
            String contents = resultCode == RESULT_OK && data != null
                    ? data.getStringExtra("qr_result") : null;
            if (contents == null || contents.trim().isEmpty()) {
                Toast.makeText(this, "Pemindaian QR dibatalkan atau tidak menemukan kode.",
                        Toast.LENGTH_SHORT).show();
            } else if (applyHostQr(contents)) {
                Toast.makeText(this, "QR Host berhasil dibaca. Menghubungkan...", Toast.LENGTH_SHORT).show();
                connectToHost();
            }
            return;
        }

        if (requestCode != REQUEST_CAPTURE) return;
        if (resultCode != RESULT_OK || data == null) {
            setStatus("Screen sharing permission was not granted.");
            return;
        }

        int maxDimension = new int[]{854, 1280, 1920}[
                Math.max(0, Math.min(2, resolutionSpinner.getSelectedItemPosition()))];
        int maxFps = new int[]{15, 24, 30, 60}[fpsSpinner.getSelectedItemPosition()];
        boolean adaptiveFps = adaptiveFpsCheck != null && adaptiveFpsCheck.isChecked();
        int fps = adaptiveFps ? Math.min(30, maxFps) : maxFps;
        int bitrate = new int[]{2, 4, 6}[bitrateSpinner.getSelectedItemPosition()] * 1_000_000;
        sessionKey = UUID.randomUUID().toString().replace("-", "");
        if (hostKeyText != null) hostKeyText.setText("Pairing key: " + sessionKey);
        if (showQrButton != null) showQrButton.setEnabled(true);

        Intent service = new Intent(this, MirrorHostService.class);
        service.putExtra("resultCode", resultCode);
        service.putExtra("projectionData", data);
        service.putExtra("maxDimension", maxDimension);
        service.putExtra("fps", fps);
        service.putExtra("maxFps", maxFps);
        service.putExtra("adaptiveFps", adaptiveFps);
        service.putExtra("bitrate", bitrate);
        service.putExtra("sessionKey", sessionKey);
        service.putExtra("audioEnabled", audioCheck.isChecked() && Build.VERSION.SDK_INT >= 29);
        service.putExtra("keepCpuAwakeWhenScreenOff",
                screenOffModeCheck != null && screenOffModeCheck.isChecked());

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(service);
        } else {
            startService(service);
        }
        hostAddress.setText("Alamat HP ini (IP lokal): " + NetworkAddress.getLocalIpv4(this)
                + "  •  Port Host: " + VIDEO_PORT);
        setStatus("Host siap. " + (adaptiveFps
                ? "Adaptive FPS aktif, batas " + maxFps + " FPS. "
                : "FPS manual " + fps + ". ")
                + (screenOffModeCheck != null && screenOffModeCheck.isChecked()
                ? "Mode layar mati siap: hubungkan Viewer lalu tekan tombol Power Host sekali. "
                : "")
                + "Salin IP Host dan pairing key ke HP Viewer.");
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        if (requestCode == REQUEST_AUDIO) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                requestScreenCapture();
            } else {
                audioCheck.setChecked(false);
                Toast.makeText(this, "Audio permission denied. Starting screen-only mirroring.", Toast.LENGTH_LONG).show();
                requestScreenCapture();
            }
        } else if (requestCode == REQUEST_CAMERA) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                launchQrScanner();
            } else {
                Toast.makeText(this, "Izin kamera diperlukan untuk memindai QR.", Toast.LENGTH_LONG).show();
            }
        }
    }
}
