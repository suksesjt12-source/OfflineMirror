package com.offlinemirror.app;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import java.net.Inet4Address;
import java.net.NetworkInterface;
import java.util.Collections;
import java.util.Enumeration;

public class MainActivity extends Activity {
    private static final int REQUEST_CAPTURE = 4101;
    private static final int PORT = 48888;

    private Spinner resolutionSpinner;
    private Spinner fpsSpinner;
    private Spinner bitrateSpinner;
    private EditText addressInput;
    private TextView hostAddress;
    private TextView statusText;

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
        subtitle.setText("Phone-to-phone screen sharing • No internet");
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

        Button startButton = makeButton("START SCREEN SHARING", Color.rgb(43, 116, 235));
        LinearLayout.LayoutParams startParams = matchWrap();
        startParams.topMargin = dp(16);
        page.addView(startButton, startParams);
        startButton.setOnClickListener(v -> {
            MediaProjectionManager manager =
                    (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
            startActivityForResult(manager.createScreenCaptureIntent(), REQUEST_CAPTURE);
        });

        hostAddress = new TextView(this);
        hostAddress.setText("Host address: " + getLocalIpv4() + ":" + PORT);
        hostAddress.setTextColor(Color.rgb(190, 205, 229));
        hostAddress.setTextSize(14);
        hostAddress.setPadding(dp(12), dp(14), dp(12), dp(14));
        LinearLayout.LayoutParams ipParams = matchWrap();
        ipParams.topMargin = dp(8);
        page.addView(hostAddress, ipParams);

        Button copyButton = makeButton("COPY HOST ADDRESS", Color.rgb(35, 48, 69));
        page.addView(copyButton, matchWrap());
        copyButton.setOnClickListener(v -> {
            String value = getLocalIpv4() + ":" + PORT;
            ClipboardManager clipboard = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            clipboard.setPrimaryClip(ClipData.newPlainText("OfflineMirror host", value));
            Toast.makeText(this, "Host address copied", Toast.LENGTH_SHORT).show();
        });

        Button stopButton = makeButton("STOP SHARING", Color.rgb(151, 54, 66));
        LinearLayout.LayoutParams stopParams = matchWrap();
        stopParams.topMargin = dp(8);
        page.addView(stopButton, stopParams);
        stopButton.setOnClickListener(v -> {
            stopService(new Intent(this, MirrorHostService.class));
            setStatus("Sharing service stopped.");
        });

        addDivider(page);
        addSectionTitle(page, "VIEWER MODE — WATCH ANOTHER PHONE");
        addLabel(page, "Host IP address (shown on the sharing phone)");
        addressInput = new EditText(this);
        addressInput.setSingleLine(true);
        addressInput.setTextColor(Color.WHITE);
        addressInput.setHintTextColor(Color.rgb(130, 145, 168));
        addressInput.setHint("Example: 192.168.43.1");
        addressInput.setTextSize(16);
        addressInput.setInputType(android.text.InputType.TYPE_CLASS_TEXT |
                android.text.InputType.TYPE_TEXT_VARIATION_URI);
        addressInput.setPadding(dp(14), dp(12), dp(14), dp(12));
        addressInput.setBackgroundTintList(android.content.res.ColorStateList.valueOf(Color.rgb(82, 116, 165)));
        page.addView(addressInput, matchWrap());

        Button connectButton = makeButton("CONNECT TO HOST", Color.rgb(24, 139, 106));
        LinearLayout.LayoutParams connectParams = matchWrap();
        connectParams.topMargin = dp(12);
        page.addView(connectButton, connectParams);
        connectButton.setOnClickListener(v -> {
            String host = addressInput.getText().toString().trim();
            if (host.isEmpty()) {
                addressInput.setError("Enter the host IP address");
                return;
            }
            Intent viewer = new Intent(this, ViewerActivity.class);
            viewer.putExtra("host", host);
            startActivity(viewer);
        });

        statusText = new TextView(this);
        statusText.setTextColor(Color.rgb(159, 177, 203));
        statusText.setTextSize(13);
        statusText.setText("Ready. Connect both phones to the same local Wi-Fi or hotspot.");
        LinearLayout.LayoutParams statusParams = matchWrap();
        statusParams.topMargin = dp(20);
        page.addView(statusText, statusParams);

        TextView note = new TextView(this);
        note.setText("How to use: turn on a hotspot or join the same Wi-Fi on both phones. Start sharing on the host phone and approve Android's screen-capture prompt. Enter the host IP on the viewer phone. Internet access is not used.");
        note.setTextColor(Color.rgb(120, 137, 164));
        note.setTextSize(12);
        note.setLineSpacing(dp(3), 1f);
        LinearLayout.LayoutParams noteParams = matchWrap();
        noteParams.topMargin = dp(18);
        page.addView(note, noteParams);

        setContentView(scroll);
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
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_CAPTURE) return;
        if (resultCode != RESULT_OK || data == null) {
            setStatus("Screen sharing permission was not granted.");
            return;
        }

        int maxDimension = resolutionSpinner.getSelectedItemPosition() == 0 ? 1280 : 1920;
        int fps = new int[]{15, 24, 30}[fpsSpinner.getSelectedItemPosition()];
        int bitrate = new int[]{2, 4, 6}[bitrateSpinner.getSelectedItemPosition()] * 1_000_000;

        Intent service = new Intent(this, MirrorHostService.class);
        service.putExtra("resultCode", resultCode);
        service.putExtra("projectionData", data);
        service.putExtra("maxDimension", maxDimension);
        service.putExtra("fps", fps);
        service.putExtra("bitrate", bitrate);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(service);
        } else {
            startService(service);
        }
        hostAddress.setText("Host address: " + getLocalIpv4() + ":" + PORT);
        setStatus("Host service started. Keep this phone connected to the same local Wi-Fi/hotspot as the viewer.");
    }
}
