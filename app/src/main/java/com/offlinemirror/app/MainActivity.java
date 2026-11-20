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
import android.net.Uri;
import android.os.Build;
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


import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.MultiFormatWriter;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.integration.android.IntentIntegrator;
import com.google.zxing.integration.android.IntentResult;

import java.net.Inet4Address;
import java.net.NetworkInterface;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class MainActivity extends Activity {
    private static final int REQUEST_CAPTURE = 4101;
    private static final int REQUEST_AUDIO = 4102;
    private static final int VIDEO_PORT = 48888;
    private static final int AUDIO_PORT = 48889;
    private static final int CONTROL_PORT = 48890;

    private Spinner resolutionSpinner;
    private Spinner fpsSpinner;
    private Spinner bitrateSpinner;
    private EditText addressInput;
    private EditText keyInput;
    private CheckBox audioCheck;
    private TextView hostAddress;
    private TextView statusText;
    private Button connectButton;
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
        subtitle.setText("Offline screen mirroring • Auto rotate");
        subtitle.setTextColor(Color.rgb(161, 174, 196));
        subtitle.setTextSize(14);
        subtitle.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams subParams = matchWrap();
        subParams.topMargin = dp(6);
        subParams.bottomMargin = dp(24);
        page.addView(subtitle, subParams);

        addSectionTitle(page, "HOST MODE — SHARE THIS PHONE");
        addLabel(page, "Video quality");
        resolutionSpinner = addSpinner(page, new String[]{"720p (recommended)", "1080p (higher bandwidth)"});
        addLabel(page, "Frame rate");
        fpsSpinner = addSpinner(page, new String[]{"15 FPS", "24 FPS", "30 FPS"});
        fpsSpinner.setSelection(2);
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

        Button startButton = makeButton("START SCREEN SHARING", Color.rgb(43, 116, 235));
        LinearLayout.LayoutParams startParams = matchWrap();
        startParams.topMargin = dp(12);
        page.addView(startButton, startParams);
        startButton.setOnClickListener(v -> beginHostFlow());

        hostAddress = new TextView(this);
        hostAddress.setText("Host address: " + getLocalIpv4() + ":" + VIDEO_PORT);
        hostAddress.setTextColor(Color.rgb(190, 205, 229));
        hostAddress.setTextSize(14);
        hostAddress.setPadding(dp(12), dp(14), dp(12), dp(14));
        LinearLayout.LayoutParams ipParams = matchWrap();
        ipParams.topMargin = dp(8);
        page.addView(hostAddress, ipParams);

        Button qrButton = makeButton("SHOW PAIRING QR", Color.rgb(35, 75, 125));
        page.addView(qrButton, matchWrap());
        qrButton.setOnClickListener(v -> {
            if (sessionKey.isEmpty()) {
                Toast.makeText(this, "Start sharing first to create a pairing code.", Toast.LENGTH_SHORT).show();
            } else {
                showPairingQr();
            }
        });

        Button copyButton = makeButton("COPY HOST ADDRESS", Color.rgb(35, 48, 69));
        LinearLayout.LayoutParams copyParams = matchWrap();
        copyParams.topMargin = dp(8);
        page.addView(copyButton, copyParams);
        copyButton.setOnClickListener(v -> {
            String value = getLocalIpv4() + ":" + VIDEO_PORT;
            ClipboardManager clipboard = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            clipboard.setPrimaryClip(ClipData.newPlainText("OfflineMirror host", value));
            Toast.makeText(this, "Host address copied", Toast.LENGTH_SHORT).show();
        });

        Button accessibilityButton = makeButton("ENABLE REMOTE TOUCH ON THIS PHONE", Color.rgb(91, 72, 132));
        LinearLayout.LayoutParams accessibilityParams = matchWrap();
        accessibilityParams.topMargin = dp(8);
        page.addView(accessibilityButton, accessibilityParams);
        accessibilityButton.setOnClickListener(v -> {
            new AlertDialog.Builder(this)
                    .setTitle("Enable Remote Touch")
                    .setMessage("Android requires you to enable OfflineMirror Remote Touch manually in Accessibility settings. This allows a paired Viewer to send taps and swipes while sharing is active. The service does not read window content.")
                    .setNegativeButton("Cancel", null)
                    .setPositiveButton("Open settings", (dialog, which) ->
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
            setStatus("Sharing service stopped. The previous QR code is no longer valid.");
        });

        addDivider(page);
        addSectionTitle(page, "VIEWER MODE — CONNECT TO ANOTHER PHONE");
        Button scanButton = makeButton("SCAN HOST QR CODE", Color.rgb(24, 139, 106));
        page.addView(scanButton, matchWrap());
        scanButton.setOnClickListener(v ->
                new IntentIntegrator(this)
                        .setDesiredBarcodeFormats(IntentIntegrator.QR_CODE)
                        .setPrompt("Scan the OfflineMirror QR shown on the Host phone")
                        .setOrientationLocked(false)
                        .initiateScan());

        addLabel(page, "Host IP address");
        addressInput = new EditText(this);
        styleInput(addressInput, "Example: 192.168.43.1");
        addressInput.setInputType(android.text.InputType.TYPE_CLASS_TEXT |
                android.text.InputType.TYPE_TEXT_VARIATION_URI);
        page.addView(addressInput, matchWrap());

        addLabel(page, "Pairing key (filled automatically by QR)");
        keyInput = new EditText(this);
        styleInput(keyInput, "Scan QR or enter the pairing key");
        keyInput.setSingleLine(true);
        page.addView(keyInput, matchWrap());

        connectButton = makeButton("CONNECT TO HOST", Color.rgb(24, 139, 106));
        LinearLayout.LayoutParams connectParams = matchWrap();
        connectParams.topMargin = dp(12);
        page.addView(connectButton, connectParams);
        connectButton.setOnClickListener(v -> connectToHost());

        statusText = new TextView(this);
        statusText.setTextColor(Color.rgb(159, 177, 203));
        statusText.setText("Ready. Connect both phones to the same local Wi-Fi or hotspot.");
        LinearLayout.LayoutParams statusParams = matchWrap();
        statusParams.topMargin = dp(20);
        page.addView(statusText, statusParams);

        TextView note = new TextView(this);
        note.setText("Remote touch requires the Host owner to enable the OfflineMirror Accessibility service. Audio playback capture requires Android 10+ and may be blocked by individual apps. Pair only with a person you trust on your local network.");
        note.setTextColor(Color.rgb(120, 137, 164));
        note.setTextSize(12);
        note.setLineSpacing(dp(3), 1f);
        LinearLayout.LayoutParams noteParams = matchWrap();
        noteParams.topMargin = dp(18);
        page.addView(note, noteParams);

        setContentView(scroll);
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
        if (host.isEmpty()) {
            addressInput.setError("Enter or scan the Host address");
            return;
        }
        if (key.length() < 16) {
            keyInput.setError("Scan the Host QR code to get the pairing key");
            return;
        }
        Intent viewer = new Intent(this, ViewerActivity.class);
        viewer.putExtra("host", host);
        viewer.putExtra("sessionKey", key);
        viewer.putExtra("audioEnabled", Build.VERSION.SDK_INT >= 29);
        startActivity(viewer);
    }

    private void showPairingQr() {
        String host = getLocalIpv4();
        if (host.equals("Check Wi-Fi settings")) {
            Toast.makeText(this, "Connect to Wi-Fi or hotspot first.", Toast.LENGTH_LONG).show();
            return;
        }
        String payload = "OFFLINEMIRROR://connect?host=" + Uri.encode(host)
                + "&video=" + VIDEO_PORT
                + "&audio=" + AUDIO_PORT
                + "&control=" + CONTROL_PORT
                + "&audioEnabled=" + (audioCheck.isChecked() && Build.VERSION.SDK_INT >= 29)
                + "&key=" + Uri.encode(sessionKey);

        try {
            Map<EncodeHintType, Object> hints = new HashMap<>();
            hints.put(EncodeHintType.MARGIN, 2);
            BitMatrix matrix = new MultiFormatWriter().encode(
                    payload, BarcodeFormat.QR_CODE, 720, 720, hints);
            int[] pixels = new int[720 * 720];
            for (int y = 0; y < 720; y++) {
                for (int x = 0; x < 720; x++) {
                    pixels[y * 720 + x] = matrix.get(x, y) ? Color.BLACK : Color.WHITE;
                }
            }
            Bitmap qr = Bitmap.createBitmap(pixels, 720, 720, Bitmap.Config.ARGB_8888);
            ImageView image = new ImageView(this);
            image.setImageBitmap(qr);
            image.setAdjustViewBounds(true);
            image.setPadding(dp(12), dp(12), dp(12), dp(12));
            TextView details = new TextView(this);
            details.setText("Host: " + host + ":" + VIDEO_PORT
                    + "\nPairing key is embedded in this QR. Keep it private.");
            details.setTextColor(Color.rgb(210, 218, 232));
            details.setPadding(dp(12), 0, dp(12), dp(12));
            LinearLayout content = new LinearLayout(this);
            content.setOrientation(LinearLayout.VERTICAL);
            content.setBackgroundColor(Color.rgb(18, 26, 40));
            content.addView(image, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(300)));
            content.addView(details, matchWrap());
            new AlertDialog.Builder(this)
                    .setTitle("OfflineMirror pairing")
                    .setView(content)
                    .setPositiveButton("Done", null)
                    .show();
        } catch (Exception error) {
            Toast.makeText(this, "Could not create QR: " + error.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void handleScannedQr(String contents) {
        try {
            Uri uri = Uri.parse(contents);
            if (!"offlinemirror".equalsIgnoreCase(uri.getScheme())
                    || !"connect".equalsIgnoreCase(uri.getHost())) {
                throw new IllegalArgumentException("This is not an OfflineMirror pairing code.");
            }
            String host = uri.getQueryParameter("host");
            String key = uri.getQueryParameter("key");
            boolean audio = Boolean.parseBoolean(uri.getQueryParameter("audioEnabled"));
            if (host == null || key == null || key.length() < 16) {
                throw new IllegalArgumentException("The pairing QR is incomplete.");
            }
            addressInput.setText(host);
            keyInput.setText(key);
            Toast.makeText(this, "Paired with " + host + (audio ? " • audio enabled" : ""), Toast.LENGTH_SHORT).show();
            Intent viewer = new Intent(this, ViewerActivity.class);
            viewer.putExtra("host", host);
            viewer.putExtra("sessionKey", key);
            viewer.putExtra("audioEnabled", audio);
            startActivity(viewer);
        } catch (Exception error) {
            Toast.makeText(this, "Invalid pairing QR: " + error.getMessage(), Toast.LENGTH_LONG).show();
        }
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

    private String getLocalIpv4() {
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            String fallback = null;
            for (NetworkInterface network : Collections.list(interfaces)) {
                if (!network.isUp() || network.isLoopback()) continue;
                String name = network.getName().toLowerCase();
                for (java.net.InetAddress address : Collections.list(network.getInetAddresses())) {
                    if (!(address instanceof Inet4Address) || address.isLoopbackAddress()) continue;
                    String ip = address.getHostAddress();
                    if (address.isSiteLocalAddress()) {
                        if (name.contains("wlan") || name.contains("wifi") || name.contains("ap")) {
                            return ip;
                        }
                        if (fallback == null) fallback = ip;
                    }
                }
            }
            if (fallback != null) return fallback;
        } catch (Exception ignored) {
        }
        return "Check Wi-Fi settings";
    }

    @Override
    @SuppressWarnings("deprecation")
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        IntentResult scanResult = IntentIntegrator.parseActivityResult(requestCode, resultCode, data);
        if (scanResult != null) {
            if (scanResult.getContents() != null) handleScannedQr(scanResult.getContents());
            return;
        }
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_CAPTURE) return;
        if (resultCode != RESULT_OK || data == null) {
            setStatus("Screen sharing permission was not granted.");
            return;
        }

        int maxDimension = resolutionSpinner.getSelectedItemPosition() == 0 ? 1280 : 1920;
        int fps = new int[]{15, 24, 30}[fpsSpinner.getSelectedItemPosition()];
        int bitrate = new int[]{2, 4, 6}[bitrateSpinner.getSelectedItemPosition()] * 1_000_000;
        sessionKey = UUID.randomUUID().toString().replace("-", "");

        Intent service = new Intent(this, MirrorHostService.class);
        service.putExtra("resultCode", resultCode);
        service.putExtra("projectionData", data);
        service.putExtra("maxDimension", maxDimension);
        service.putExtra("fps", fps);
        service.putExtra("bitrate", bitrate);
        service.putExtra("sessionKey", sessionKey);
        service.putExtra("audioEnabled", audioCheck.isChecked() && Build.VERSION.SDK_INT >= 29);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(service);
        } else {
            startService(service);
        }
        hostAddress.setText("Host address: " + getLocalIpv4() + ":" + VIDEO_PORT);
        setStatus("Host is starting. Show the pairing QR to connect a Viewer.");
        showPairingQr();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_AUDIO) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                requestScreenCapture();
            } else {
                audioCheck.setChecked(false);
                Toast.makeText(this, "Audio permission denied. Starting screen-only mirroring.", Toast.LENGTH_LONG).show();
                requestScreenCapture();
            }
        }
    }
}
