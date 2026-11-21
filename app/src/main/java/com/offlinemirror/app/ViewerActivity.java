package com.offlinemirror.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.RectF;
import android.graphics.SurfaceTexture;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.media.MediaCodec;
import android.media.MediaFormat;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.provider.Settings;
import android.content.pm.PackageManager;
import android.Manifest;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Set;

public class ViewerActivity extends Activity implements TextureView.SurfaceTextureListener {
    private static final int VIDEO_PORT = 48888;
    private static final int AUDIO_PORT = 48889;
    private static final int CONTROL_PORT = 48890;
    private static final int MAGIC = 0x4F4D4952;
    private static final int CONTROL_MAGIC = 0x4F4D4354;
    private static final int REQUEST_BLUETOOTH = 7721;
    private static final int REQUEST_BLUETOOTH_DISCOVERABLE = 7722;

    private TextureView textureView;
    private Surface videoSurface;
    private FrameLayout videoFrame;
    private int streamWidth;
    private int streamHeight;
    private TextView status;
    private TextView gamepadStatus;
    private Button gamepadButton;
    private Button bluetoothButton;
    private GamepadOverlayView gamepadOverlay;
    private BluetoothHidGamepad bluetoothGamepad;
    private BluetoothHidGamepad.InputMode selectedHidMode = BluetoothHidGamepad.InputMode.GAMEPAD;
    private BluetoothDevice selectedHidDevice;
    private TouchOverlayView touchOverlay;
    private Button layoutToolButton;
    private Button overlayToggleButton;
    private boolean gamepadInputMode = true;
    private boolean layoutEditMode;
    private boolean gamepadWasHiddenBeforeLayout;
    private volatile long receiveGeneration;
    private volatile String bluetoothStatus =
            "Jaringan 2 • Bluetooth HID belum tersambung • tekan PAIR BT";
    private boolean controlEnabled = true;
    private boolean lowLatencyMode = true;
    private boolean showPerformance = true;
    private float gamepadSensitivity = 1.0f;
    private float gamepadDeadzone = 0.04f;
    private int gamepadResponseCurve = 0;
    private int gamepadPreset = 0;
    private volatile long pingSentAt = 0L;
    private volatile long pingRttMs = -1L;
    private volatile Thread pingThread;
    private String host;
    private String sessionKey = "";
    private boolean audioEnabled;
    private int videoPort = VIDEO_PORT;
    private int audioPort = AUDIO_PORT;
    private int controlPort = CONTROL_PORT;
    private String viewerAddress = "IP belum tersedia";
    private volatile String lastForegroundPackage = "";

    private volatile boolean running = false;
    private volatile Socket videoSocket;
    private volatile Socket audioSocket;
    private volatile Socket controlSocket;
    private volatile DataOutputStream controlOutput;
    private volatile Thread receiveThread;
    private volatile Thread audioThread;
    private volatile Thread controlThread;
    private volatile boolean controlLoopRunning = true;
    private volatile String videoStatus = "Menyiapkan koneksi...";
    private volatile String audioStatus = "Audio: nonaktif";
    private volatile String controlStatus = "Remote Control: menghubungkan";

    private int dp(float value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        host = getIntent().getStringExtra("host");
        sessionKey = getIntent().getStringExtra("sessionKey");
        audioEnabled = getIntent().getBooleanExtra("audioEnabled", false);
        controlEnabled = getIntent().getBooleanExtra("controlEnabled", true);
        android.content.SharedPreferences preferences =
                getSharedPreferences("offline_mirror_settings", MODE_PRIVATE);
        lowLatencyMode = preferences.getBoolean("low_latency", true);
        showPerformance = preferences.getBoolean("performance_overlay", true);
        gamepadSensitivity = preferences.getFloat("gamepad_sensitivity", 1.0f);
        gamepadDeadzone = preferences.getFloat("gamepad_deadzone", 0.04f);
        gamepadResponseCurve = preferences.getInt("gamepad_response_curve", 0);
        gamepadPreset = preferences.getInt("gamepad_preset", 0);
        videoPort = getIntent().getIntExtra("videoPort", VIDEO_PORT);
        audioPort = getIntent().getIntExtra("audioPort", AUDIO_PORT);
        controlPort = getIntent().getIntExtra("controlPort", CONTROL_PORT);
        viewerAddress = NetworkAddress.getLocalIpv4(this);

        getWindow().setStatusBarColor(Color.BLACK);
        getWindow().setNavigationBarColor(Color.BLACK);
        enableImmersiveViewer();

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);

        status = new TextView(this);
        status.setTextColor(Color.WHITE);
        status.setTextSize(11);
        status.setMaxLines(3);
        status.setGravity(Gravity.CENTER_VERTICAL);
        status.setPadding(dp(12), dp(5), dp(72), dp(5));
        FrameLayout.LayoutParams statusParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(52), Gravity.TOP);
        status.setBackgroundColor(0x99000000);
        root.addView(status, statusParams);

        LinearLayout gamepadBar = new LinearLayout(this);
        gamepadBar.setOrientation(LinearLayout.HORIZONTAL);
        gamepadBar.setGravity(Gravity.CENTER_VERTICAL);
        gamepadBar.setPadding(dp(8), 0, dp(8), 0);
        gamepadStatus = new TextView(this);
        gamepadStatus.setText(bluetoothStatus);
        gamepadStatus.setTextColor(Color.rgb(230, 238, 250));
        gamepadStatus.setTextSize(9);
        gamepadStatus.setMaxLines(2);
        gamepadStatus.setMaxWidth(dp(190));
        gamepadBar.addView(gamepadStatus, new LinearLayout.LayoutParams(
                dp(180), ViewGroup.LayoutParams.MATCH_PARENT));
        gamepadButton = new Button(this);
        gamepadButton.setText("PAIR BT");
        gamepadButton.setEnabled(false);
        gamepadButton.setTextSize(9);
        gamepadButton.setMinHeight(dp(34));
        gamepadButton.setMinimumHeight(dp(34));
        gamepadBar.addView(gamepadButton, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(36)));
        gamepadBar.setBackgroundColor(0xBB111923);
        gamepadBar.setPadding(dp(8), 0, dp(8), 0);

        videoFrame = new FrameLayout(this);
        videoFrame.setBackgroundColor(Color.BLACK);
        textureView = new TextureView(this);
        textureView.setOpaque(true);
        textureView.setSurfaceTextureListener(this);
        videoFrame.addView(textureView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT,
                Gravity.CENTER));
        videoFrame.addOnLayoutChangeListener((view, left, top, right, bottom,
                                               oldLeft, oldTop, oldRight, oldBottom) -> {
            updateVideoSurfaceLayout();
        });

        gamepadOverlay = new GamepadOverlayView(this);
        gamepadOverlay.setSensitivity(gamepadSensitivity);
        gamepadOverlay.setDeadzone(gamepadDeadzone);
        gamepadOverlay.setResponseCurve(gamepadResponseCurve);
        gamepadOverlay.setLayoutPreset(gamepadPreset);
        for (int elementId : GamepadOverlayView.LAYOUT_ELEMENT_IDS) {
            float x = preferences.getFloat("gamepad_element_" + elementId + "_x", 0f);
            float y = preferences.getFloat("gamepad_element_" + elementId + "_y", 0f);
            gamepadOverlay.setElementOffset(elementId, x, y);
        }
        gamepadOverlay.setLayoutChangedListener((elementId, x, y) ->
                preferences.edit()
                        .putFloat("gamepad_element_" + elementId + "_x", x)
                        .putFloat("gamepad_element_" + elementId + "_y", y).apply());
        gamepadOverlay.setListener(new GamepadOverlayView.Listener() {
            @Override
            public void onGamepadState(int buttons, float leftX, float leftY,
                                       float rightX, float rightY) {
                if (bluetoothGamepad != null) {
                    bluetoothGamepad.sendState(buttons, leftX, leftY, rightX, rightY);
                }
            }

        });
        touchOverlay = new TouchOverlayView(this);
        touchOverlay.setListener(this::sendTouchContacts);
        videoFrame.addView(touchOverlay, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        videoFrame.addView(gamepadOverlay, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        videoFrame.setClipChildren(true);
        videoFrame.setClipToPadding(true);
        root.addView(videoFrame, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // Wi-Fi carries mirroring; Bluetooth registers one independent HID profile at a time.
        FrameLayout.LayoutParams gamepadBarParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(44), Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        gamepadBarParams.topMargin = dp(48);
        root.addView(gamepadBar, gamepadBarParams);

        android.widget.HorizontalScrollView toolsScroll = new android.widget.HorizontalScrollView(this);
        toolsScroll.setHorizontalScrollBarEnabled(false);
        LinearLayout toolsBar = new LinearLayout(this);
        toolsBar.setOrientation(LinearLayout.HORIZONTAL);
        toolsBar.setGravity(Gravity.CENTER);
        toolsBar.setBackgroundColor(0xBB111923);
        String[] toolNames = {"SETTINGS", "BLUETOOTH", "LAYOUT",
                "VOL -", "VOL +", "MEDIA", "CLIPBOARD"};
        Button settingsTool = null;
        Button layoutTool = null;
        bluetoothButton = null;
        for (String toolName : toolNames) {
            Button tool = new Button(this);
            tool.setText(toolName);
            tool.setTextSize(9);
            tool.setMinHeight(dp(34));
            tool.setMinimumHeight(dp(34));
            LinearLayout.LayoutParams toolParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, dp(36));
            toolParams.setMargins(dp(2), 0, dp(2), 0);
            toolsBar.addView(tool, toolParams);
            if ("SETTINGS".equals(toolName)) settingsTool = tool;
            else if ("BLUETOOTH".equals(toolName)) bluetoothButton = tool;
            else if ("LAYOUT".equals(toolName)) layoutTool = tool;
            else if ("VOL -".equals(toolName)) tool.setOnClickListener(v -> sendHostAction(6, 1));
            else if ("VOL +".equals(toolName)) tool.setOnClickListener(v -> sendHostAction(6, 2));
            else if ("MEDIA".equals(toolName)) tool.setOnClickListener(v -> sendHostAction(6, 3));
            else if ("CLIPBOARD".equals(toolName)) tool.setOnClickListener(v -> sendClipboardToHost());
        }
        if (settingsTool != null) settingsTool.setOnClickListener(v -> showGamepadSettings());
        if (bluetoothButton != null) bluetoothButton.setOnClickListener(v -> showBluetoothDevicePicker());
        layoutToolButton = layoutTool;
        if (layoutToolButton != null) {
            layoutToolButton.setOnClickListener(v -> toggleLayoutEditMode(layoutToolButton));
        }
        toolsScroll.addView(toolsBar);
        FrameLayout.LayoutParams toolsParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(40), Gravity.TOP);
        toolsParams.topMargin = dp(92);
        root.addView(toolsScroll, toolsParams);

        Button overlayToggle = new Button(this);
        overlayToggleButton = overlayToggle;
        overlayToggle.setText("MODE: GAMEPAD");
        overlayToggle.setTextSize(10);
        FrameLayout.LayoutParams toggleParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(42), Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        toggleParams.bottomMargin = dp(10);
        root.addView(overlayToggle, toggleParams);
        final android.content.SharedPreferences modePrefs = preferences;
        final float savedToggleX = preferences.getFloat("mode_toggle_x", -1f);
        final float savedToggleY = preferences.getFloat("mode_toggle_y", -1f);
        overlayToggle.post(() -> {
            if (savedToggleX >= 0f && savedToggleY >= 0f) {
                FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) overlayToggle.getLayoutParams();
                lp.gravity = Gravity.TOP | Gravity.LEFT;
                lp.leftMargin = Math.max(0, Math.min(root.getWidth() - overlayToggle.getWidth(),
                        Math.round(savedToggleX * root.getWidth())));
                lp.topMargin = Math.max(0, Math.min(root.getHeight() - overlayToggle.getHeight(),
                        Math.round(savedToggleY * root.getHeight())));
                lp.bottomMargin = 0;
                overlayToggle.setLayoutParams(lp);
            }
        });
        final float[] dragStart = new float[2];
        final float[] viewStart = new float[2];
        final boolean[] dragged = {false};
        overlayToggle.setOnTouchListener((view, event) -> {
            FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) view.getLayoutParams();
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    dragStart[0] = event.getRawX(); dragStart[1] = event.getRawY();
                    viewStart[0] = view.getX(); viewStart[1] = view.getY();
                    dragged[0] = false;
                    return true;
                case MotionEvent.ACTION_MOVE:
                    float dx = event.getRawX() - dragStart[0];
                    float dy = event.getRawY() - dragStart[1];
                    if (Math.abs(dx) + Math.abs(dy) > dp(7)) dragged[0] = true;
                    if (dragged[0]) {
                        lp.gravity = Gravity.TOP | Gravity.LEFT;
                        lp.leftMargin = Math.max(0, Math.min(root.getWidth() - view.getWidth(),
                                Math.round(viewStart[0] + dx)));
                        lp.topMargin = Math.max(0, Math.min(root.getHeight() - view.getHeight(),
                                Math.round(viewStart[1] + dy)));
                        lp.rightMargin = 0; lp.bottomMargin = 0;
                        view.setLayoutParams(lp);
                    }
                    return true;
                case MotionEvent.ACTION_UP:
                    if (dragged[0]) {
                        float nx = root.getWidth() > 0 ? view.getX() / root.getWidth() : 0f;
                        float ny = root.getHeight() > 0 ? view.getY() / root.getHeight() : 0f;
                        modePrefs.edit().putFloat("mode_toggle_x", nx)
                                .putFloat("mode_toggle_y", ny).apply();
                    } else {
                        view.performClick();
                    }
                    return true;
                case MotionEvent.ACTION_CANCEL:
                    return true;
            }
            return true;
        });
        gamepadButton.setEnabled(true);
        gamepadButton.setOnClickListener(button -> showBluetoothDevicePicker());
        if (!controlEnabled) {
            gamepadOverlay.setVisibility(View.GONE);
            touchOverlay.setVisibility(View.GONE);
            overlayToggle.setVisibility(View.GONE);
            gamepadBar.setVisibility(View.GONE);
            toolsScroll.setVisibility(View.GONE);
            controlStatus = "Mode penonton • video saja";
        } else {
            // Gamepad is the upper layer. Its unhandled screen areas fall through
            // to TouchOverlayView, allowing ordinary touchscreen gestures too.
            gamepadOverlay.setVisibility(View.VISIBLE);
            touchOverlay.setVisibility(View.VISIBLE);
        }
        overlayToggle.setOnClickListener(button -> showBluetoothDevicePicker());

        Button hudToggle = new Button(this);
        hudToggle.setTextSize(10);
        hudToggle.setMinWidth(dp(52));
        hudToggle.setMinimumWidth(dp(52));
        hudToggle.setMinHeight(dp(40));
        hudToggle.setMinimumHeight(dp(40));
        FrameLayout.LayoutParams hudParams = new FrameLayout.LayoutParams(
                dp(58), dp(42), Gravity.TOP | Gravity.END);
        hudParams.topMargin = dp(4);
        hudParams.rightMargin = dp(6);
        root.addView(hudToggle, hudParams);
        final boolean[] hudShown = {preferences.getBoolean("hud_visible", true)};
        Runnable applyHudVisibility = () -> {
            int visibility = hudShown[0] ? View.VISIBLE : View.GONE;
            status.setVisibility(visibility);
            gamepadBar.setVisibility(visibility);
            toolsScroll.setVisibility(visibility);
            hudToggle.setText(hudShown[0] ? "HUD −" : "HUD +");
        };
        applyHudVisibility.run();
        if (!controlEnabled) {
            status.setVisibility(View.GONE);
            gamepadBar.setVisibility(View.GONE);
            toolsScroll.setVisibility(View.GONE);
            hudToggle.setVisibility(View.GONE);
        }
        hudToggle.setOnClickListener(button -> {
            hudShown[0] = !hudShown[0];
            applyHudVisibility.run();
            preferences.edit().putBoolean("hud_visible", hudShown[0]).apply();
        });

        setContentView(root);
        setInputMode(selectedHidMode == BluetoothHidGamepad.InputMode.GAMEPAD, "Awal");

        videoStatus = "Menghubungkan ke Host " + host + "...";
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
    public void onSurfaceTextureAvailable(SurfaceTexture surfaceTexture, int width, int height) {
        videoSurface = new Surface(surfaceTexture);
        updateVideoSurfaceLayout();
        startReceiving(videoSurface);
        if (controlEnabled) startControlConnection();
    }

    @Override
    public void onSurfaceTextureSizeChanged(SurfaceTexture surfaceTexture, int width, int height) {
        updateVideoSurfaceLayout();
    }

    @Override
    public boolean onSurfaceTextureDestroyed(SurfaceTexture surfaceTexture) {
        stopReceiving();
        // The decoder thread releases its Surface after MediaCodec has stopped.
        videoSurface = null;
        return true;
    }

    @Override
    public void onSurfaceTextureUpdated(SurfaceTexture surfaceTexture) {
        // Rendering is handled directly by MediaCodec.
    }

    private synchronized void startReceiving(Surface surface) {
        if (surface == null || !surface.isValid() || running) return;
        running = true;
        long generation = ++receiveGeneration;
        receiveThread = new Thread(
                () -> receiveLoop(surface, generation), "OfflineMirror-Viewer");
        receiveThread.start();
    }

    private void startControlConnection() {
        if (controlThread != null && controlThread.isAlive()) return;
        controlLoopRunning = true;
        controlThread = new Thread(() -> {
            while (controlLoopRunning) {
                Socket candidate = new Socket();
                try {
                    candidate.setTcpNoDelay(true);
                    candidate.setKeepAlive(true);
                    candidate.connect(new InetSocketAddress(host, controlPort), 8_000);

                    DataOutputStream output = new DataOutputStream(
                            new BufferedOutputStream(candidate.getOutputStream()));
                    output.writeUTF(sessionKey);
                    output.flush();

                    DataInputStream input = new DataInputStream(
                            new BufferedInputStream(candidate.getInputStream()));
                    int protocol = input.readInt();
                    if (protocol != CONTROL_MAGIC) {
                        throw new IllegalStateException("Versi protokol kontrol Host tidak cocok");
                    }

                    controlSocket = candidate;
                    controlOutput = output;
                    controlStatus = "Wi-Fi utilities connected";
                    updateStatus();
                    android.util.Log.i("OfflineMirror", "Remote Control channel authenticated");
                    startPingLoop(output, candidate);

                    // The utility socket carries only utility commands and ping.
                    while (controlLoopRunning && !candidate.isClosed()) {
                        int state = input.readUnsignedByte();
                        if (state == 3) {
                            String packageName = input.readUTF();
                            handleForegroundAppEvent(packageName);
                            continue;
                        }
                        if (state == 2) {
                            long sentAt = pingSentAt;
                            if (sentAt > 0L) {
                                pingRttMs = Math.max(0L, SystemClock.elapsedRealtime() - sentAt);
                                pingSentAt = 0L;
                            }
                            updateStatus();
                            continue;
                        }
                        if (state != 0 && state != 1) {
                            throw new IllegalStateException("Status kontrol dari Host tidak valid");
                        }
                        controlStatus = state == 1
                                ? "Wi-Fi utility command accepted"
                                : "Wi-Fi utility command rejected";
                        updateStatus();
                    }
                } catch (Exception error) {
                    if (controlLoopRunning) {
                        controlStatus = "Kontrol terputus • mencoba menyambungkan ulang";
                        updateStatus();
                        android.util.Log.w("OfflineMirror",
                                "Remote Control retry: " + error.getMessage());
                    }
                } finally {
                    if (controlSocket == candidate) {
                        controlSocket = null;
                        controlOutput = null;
                    }
                    try { candidate.close(); } catch (Exception ignored) {}
                }

                if (controlLoopRunning) {
                    try {
                        Thread.sleep(1200);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        controlLoopRunning = false;
                    }
                }
            }
        }, "OfflineMirror-Remote-Control-Client");
        controlThread.setDaemon(true);
        controlThread.start();
    }

    private void handleForegroundAppEvent(String packageName) {
        if (packageName == null || packageName.isEmpty()
                || packageName.equals(lastForegroundPackage)) return;
        lastForegroundPackage = packageName;
        runOnUiThread(() -> {
            android.content.SharedPreferences prefs =
                    getSharedPreferences("offline_mirror_settings", MODE_PRIVATE);
            int savedProfile = prefs.getInt("app_profile_" + packageName, -1);
            String label = packageName;
            try {
                label = getPackageManager().getApplicationLabel(
                        getPackageManager().getApplicationInfo(packageName, 0)).toString();
            } catch (Exception ignored) {}
            if (savedProfile == 0 || savedProfile == 1) {
                boolean useGamepad = savedProfile == 0;
                boolean changed = gamepadInputMode != useGamepad;
                if (changed) {
                    setInputMode(useGamepad, "Otomatis • " + label);
                    if (bluetoothGamepad != null) {
                        bluetoothGamepad.switchProfile(selectedHidDevice, selectedHidMode);
                    }
                }
                controlStatus = "Auto profile: " + label + " → "
                        + (useGamepad ? "Gamepad" : "Touch HID");
            } else {
                controlStatus = "Aplikasi Host: " + label + " • belum ada profil otomatis";
            }
            updateStatus();
        });
    }

    private void saveProfileForCurrentApp(int profile) {
        String packageName = lastForegroundPackage;
        if (packageName == null || packageName.isEmpty()) {
            Toast.makeText(this, "Belum menerima nama aplikasi dari Host. Pastikan Usage Access aktif di HP Host.",
                    Toast.LENGTH_LONG).show();
            return;
        }
        getSharedPreferences("offline_mirror_settings", MODE_PRIVATE).edit()
                .putInt("app_profile_" + packageName, profile).apply();
        boolean useGamepad = profile == 0;
        setInputMode(useGamepad, "Profil aplikasi tersimpan");
        if (bluetoothGamepad != null) {
            bluetoothGamepad.switchProfile(selectedHidDevice, selectedHidMode);
        }
        String label = packageName;
        try {
            label = getPackageManager().getApplicationLabel(
                    getPackageManager().getApplicationInfo(packageName, 0)).toString();
        } catch (Exception ignored) {}
        Toast.makeText(this, "Profil " + (useGamepad ? "Gamepad" : "Touch HID")
                + " disimpan untuk " + label, Toast.LENGTH_LONG).show();
    }

    private void startPingLoop(DataOutputStream output, Socket socket) {
        Thread previous = pingThread;
        if (previous != null) previous.interrupt();
        pingThread = new Thread(() -> {
            while (controlLoopRunning && !socket.isClosed()) {
                try {
                    synchronized (output) {
                        pingSentAt = SystemClock.elapsedRealtime();
                        output.writeByte(7);
                        output.flush();
                    }
                    Thread.sleep(2000L);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return;
                } catch (Exception error) {
                    return;
                }
            }
        }, "OfflineMirror-Network-Ping");
        pingThread.setDaemon(true);
        pingThread.start();
    }

    private void sendHostAction(int type, int action) {
        if (!controlEnabled || controlOutput == null) return;
        DataOutputStream output = controlOutput;
        try {
            synchronized (output) {
                output.writeByte(type);
                output.writeByte(action);
                output.flush();
            }
        } catch (Exception error) {
            android.util.Log.w("OfflineMirror", "Could not send remote host action", error);
        }
    }

    private void setInputMode(boolean useGamepad, String source) {
        if (!controlEnabled) return;
        if (gamepadInputMode == useGamepad && overlayToggleButton != null) return;

        if (!useGamepad && gamepadOverlay != null) gamepadOverlay.releaseAllInputs();
        if (touchOverlay != null) touchOverlay.cancelTouches();

        gamepadInputMode = useGamepad;
        selectedHidMode = useGamepad ? BluetoothHidGamepad.InputMode.GAMEPAD : BluetoothHidGamepad.InputMode.TOUCH;
        if (touchOverlay != null) touchOverlay.setVisibility(View.VISIBLE);
        if (gamepadOverlay != null) {
            gamepadOverlay.setVisibility(useGamepad ? View.VISIBLE : View.GONE);
        }
        if (overlayToggleButton != null) {
            overlayToggleButton.setText(useGamepad ? "MODE: GAMEPAD" : "MODE: TOUCH HID");
        }
        controlStatus = source + ": " + (useGamepad ? "Gamepad HID" : "Touch HID") + " dipilih; profil Bluetooth didaftarkan terpisah.";
        updateStatus();
    }

    private void sendClipboardToHost() {
        if (!controlEnabled) return;
        ClipboardManager clipboard = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        if (clipboard == null || !clipboard.hasPrimaryClip()
                || clipboard.getPrimaryClip().getItemCount() == 0) {
            Toast.makeText(this, "Clipboard Viewer kosong", Toast.LENGTH_SHORT).show();
            return;
        }
        CharSequence text = clipboard.getPrimaryClip().getItemAt(0).coerceToText(this);
        if (text == null || text.length() == 0) {
            Toast.makeText(this, "Clipboard tidak berisi teks", Toast.LENGTH_SHORT).show();
            return;
        }
        String value = text.toString();
        if (value.length() > 4096) {
            Toast.makeText(this, "Teks clipboard maksimal 4096 karakter", Toast.LENGTH_LONG).show();
            return;
        }
        DataOutputStream output = controlOutput;
        if (output == null) return;
        try {
            synchronized (output) {
                output.writeByte(5);
                output.writeUTF(value);
                output.flush();
            }
            Toast.makeText(this, "Teks clipboard dikirim ke Host", Toast.LENGTH_SHORT).show();
        } catch (Exception error) {
            Toast.makeText(this, "Gagal mengirim clipboard", Toast.LENGTH_SHORT).show();
        }
    }

    private void showGamepadSettings() {
        LinearLayout panel = new LinearLayout(this);
        panel.setPadding(dp(18), dp(10), dp(18), dp(4));
        panel.setOrientation(LinearLayout.VERTICAL);

        TextView mappingInfo = new TextView(this);
        mappingInfo.setText("Pilih profil Bluetooth Touch HID atau Gamepad HID melalui tombol BLUETOOTH. Pengiriman sentuhan HID masih bergantung pada dukungan digitizer di Android Host. Atur layout dan respons analog di bawah.");
        mappingInfo.setTextSize(12);
        panel.addView(mappingInfo);

        TextView autoProfileInfo = new TextView(this);
        autoProfileInfo.setText("Aplikasi Host terdeteksi otomatis melalui Usage Access. Untuk menyimpan profil aplikasi yang sedang aktif, pilih tombol berikut.");
        autoProfileInfo.setTextSize(12);
        autoProfileInfo.setPadding(0, dp(8), 0, dp(4));
        panel.addView(autoProfileInfo);
        Button saveAppProfile = new Button(this);
        saveAppProfile.setText("SIMPAN PROFIL UNTUK APLIKASI HOST AKTIF");
        saveAppProfile.setOnClickListener(v -> new AlertDialog.Builder(this)
                .setTitle("Profil untuk " + (lastForegroundPackage.isEmpty()
                        ? "aplikasi aktif" : lastForegroundPackage))
                .setItems(new String[]{"Gamepad HID", "Touch HID"}, (dialog, which) ->
                        saveProfileForCurrentApp(which))
                .setNegativeButton("Batal", null).show());
        panel.addView(saveAppProfile);

        TextView presetLabel = new TextView(this);
        presetLabel.setText("Layout gamepad");
        panel.addView(presetLabel);
        Spinner preset = new Spinner(this);
        String[] presets = {"Classic", "Compact", "Left-handed"};
        preset.setAdapter(new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_dropdown_item, presets));
        preset.setSelection(gamepadPreset);
        panel.addView(preset);

        TextView sensitivityLabel = new TextView(this);
        sensitivityLabel.setText("Sensitivitas analog");
        panel.addView(sensitivityLabel);
        SeekBar sensitivity = new SeekBar(this);
        sensitivity.setMax(130);
        sensitivity.setProgress(Math.max(0, Math.min(130,
                Math.round((gamepadSensitivity - 0.5f) * 100f))));
        panel.addView(sensitivity);

        TextView deadzoneLabel = new TextView(this);
        deadzoneLabel.setText("Deadzone analog: " + Math.round(gamepadDeadzone * 100f) + "%");
        panel.addView(deadzoneLabel);
        SeekBar deadzone = new SeekBar(this);
        deadzone.setMax(20);
        deadzone.setProgress(Math.round(gamepadDeadzone * 100f));
        deadzone.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar bar, int value, boolean fromUser) {
                deadzoneLabel.setText("Deadzone analog: " + value + "%");
            }
            @Override public void onStartTrackingTouch(SeekBar bar) {}
            @Override public void onStopTrackingTouch(SeekBar bar) {}
        });
        panel.addView(deadzone);

        TextView curveLabel = new TextView(this);
        curveLabel.setText("Kurva respons analog");
        panel.addView(curveLabel);
        Spinner curve = new Spinner(this);
        curve.setAdapter(new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_dropdown_item,
                new String[]{"Linear", "Dynamic", "Precision"}));
        curve.setSelection(gamepadResponseCurve);
        panel.addView(curve);

        CheckBox lowLatency = new CheckBox(this);
        lowLatency.setText("Ultra Low Latency (decoder & buffer kecil)");
        lowLatency.setChecked(lowLatencyMode);
        panel.addView(lowLatency);

        CheckBox performance = new CheckBox(this);
        performance.setText("Tampilkan FPS, bitrate, ping, dan RAM aplikasi");
        performance.setChecked(showPerformance);
        panel.addView(performance);

        android.widget.ScrollView settingsScroll = new android.widget.ScrollView(this);
        settingsScroll.setFillViewport(false);
        settingsScroll.addView(panel);
        new AlertDialog.Builder(this).setTitle("Pengaturan kontrol")
                .setView(settingsScroll)
                .setNegativeButton("Batal", null)
                .setPositiveButton("Simpan", (dialog, which) -> {
                    gamepadPreset = preset.getSelectedItemPosition();
                    gamepadSensitivity = 0.5f + sensitivity.getProgress() / 100f;
                    gamepadDeadzone = deadzone.getProgress() / 100f;
                    gamepadResponseCurve = curve.getSelectedItemPosition();
                    lowLatencyMode = lowLatency.isChecked();
                    showPerformance = performance.isChecked();
                    getSharedPreferences("offline_mirror_settings", MODE_PRIVATE).edit()
                            .putInt("gamepad_preset", gamepadPreset)
                            .putFloat("gamepad_sensitivity", gamepadSensitivity)
                            .putFloat("gamepad_deadzone", gamepadDeadzone)
                            .putInt("gamepad_response_curve", gamepadResponseCurve)
                            .putBoolean("low_latency", lowLatencyMode)
                            .putBoolean("performance_overlay", showPerformance)
                            .apply();
                    gamepadOverlay.setLayoutPreset(gamepadPreset);
                    gamepadOverlay.setSensitivity(gamepadSensitivity);
                    gamepadOverlay.setDeadzone(gamepadDeadzone);
                    gamepadOverlay.setResponseCurve(gamepadResponseCurve);
                    Toast.makeText(this, "Pengaturan disimpan", Toast.LENGTH_SHORT).show();
                }).show();
    }

    private void showBluetoothDevicePicker() {
        final String[] modes = {"Gamepad HID • analog + tombol", "Touch HID • sentuhan + swipe"};
        new AlertDialog.Builder(this).setTitle("Pilih mode Bluetooth terpisah")
                .setItems(modes, (dialog, which) -> {
                    selectedHidMode = which == 0 ? BluetoothHidGamepad.InputMode.GAMEPAD
                            : BluetoothHidGamepad.InputMode.TOUCH;
                    setInputMode(selectedHidMode == BluetoothHidGamepad.InputMode.GAMEPAD, "Mode Bluetooth");
                    showBluetoothDevicePickerForMode();
                }).setNegativeButton("Batal", null).show();
    }

    private void showBluetoothDevicePickerForMode() {
        if (Build.VERSION.SDK_INT < 28) {
            Toast.makeText(this, "Bluetooth HID membutuhkan Android 9 atau lebih baru",
                    Toast.LENGTH_LONG).show();
            return;
        }
        if (Build.VERSION.SDK_INT >= 31
                && (checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)
                    != PackageManager.PERMISSION_GRANTED
                    || checkSelfPermission(Manifest.permission.BLUETOOTH_ADVERTISE)
                    != PackageManager.PERMISSION_GRANTED)) {
            requestPermissions(new String[]{
                    Manifest.permission.BLUETOOTH_CONNECT,
                    Manifest.permission.BLUETOOTH_ADVERTISE
            }, REQUEST_BLUETOOTH);
            return;
        }
        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        if (adapter == null) {
            Toast.makeText(this, "Perangkat ini tidak memiliki Bluetooth", Toast.LENGTH_LONG).show();
            return;
        }
        if (!adapter.isEnabled()) {
            new AlertDialog.Builder(this).setTitle("Aktifkan Bluetooth")
                    .setMessage("Aktifkan Bluetooth pada HP pengendali dan Host, lalu pairing dari pengaturan Bluetooth Android.")
                    .setNegativeButton("Batal", null)
                    .setPositiveButton("Pengaturan Bluetooth", (d, w) ->
                            startActivity(new Intent(Settings.ACTION_BLUETOOTH_SETTINGS)))
                    .show();
            return;
        }
        Set<BluetoothDevice> bonded;
        try {
            bonded = adapter.getBondedDevices();
        } catch (SecurityException denied) {
            Toast.makeText(this, "Izin Bluetooth belum diberikan", Toast.LENGTH_LONG).show();
            return;
        }
        if (bonded == null || bonded.isEmpty()) {
            startBluetoothGamepad(null);
            new AlertDialog.Builder(this).setTitle("Bluetooth HID siap dipair")
                    .setMessage("Mode Bluetooth yang dipilih didaftarkan sebagai profil HID tersendiri. Pada HP Host, hapus pairing OfflineMirror lama jika masih tersimpan, lalu cari dan pair perangkat yang baru muncul. Saat berganti mode, profil lama dihentikan dan profil baru didaftarkan. Biarkan Viewer tetap terbuka saat pairing. Dukungan HID bergantung Android dan firmware Host.")
                    .setPositiveButton("Mengerti", null)
                    .setNeutralButton("Setelan Bluetooth Viewer", (d, w) ->
                            startActivity(new Intent(Settings.ACTION_BLUETOOTH_SETTINGS)))
                    .show();
            return;
        }
        ArrayList<BluetoothDevice> devices = new ArrayList<>(bonded);
        String[] labels = new String[devices.size() + 1];
        labels[0] = "PAIR HP HOST BARU • buat Viewer terlihat";
        for (int i = 0; i < devices.size(); i++) {
            BluetoothDevice device = devices.get(i);
            String name = device.getName();
            labels[i + 1] = (name == null || name.isEmpty() ? "Bluetooth device" : name)
                    + " • " + device.getAddress();
        }
        new AlertDialog.Builder(this).setTitle("Pilih HP Host untuk " + (selectedHidMode == BluetoothHidGamepad.InputMode.GAMEPAD ? "Gamepad HID" : "Touch HID"))
                .setItems(labels, (dialog, which) -> {
                    if (which == 0) startBluetoothGamepad(null);
                    else startBluetoothGamepad(devices.get(which - 1));
                })
                .setNeutralButton("Pengaturan Bluetooth", (dialog, which) ->
                        startActivity(new Intent(Settings.ACTION_BLUETOOTH_SETTINGS)))
                .setNegativeButton("Batal", null).show();
    }

    private void requestBluetoothDiscoverable() {
        if (Build.VERSION.SDK_INT >= 31
                && checkSelfPermission(Manifest.permission.BLUETOOTH_ADVERTISE)
                    != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, "Izin Bluetooth Advertise diperlukan agar Vivo terlihat oleh HP lain.",
                    Toast.LENGTH_LONG).show();
            return;
        }
        try {
            Intent discoverable = new Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE);
            discoverable.putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 300);
            startActivityForResult(discoverable, REQUEST_BLUETOOTH_DISCOVERABLE);
            bluetoothStatus = "Izinkan perangkat terlihat selama 5 menit pada dialog Android.";
            updateStatus();
        } catch (Exception error) {
            bluetoothStatus = "Gagal meminta mode terlihat: " + error.getMessage();
            updateStatus();
            android.util.Log.e("OfflineMirror", "Could not request Bluetooth discoverability", error);
        }
    }

    @Override
    @SuppressWarnings("deprecation")
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_BLUETOOTH_DISCOVERABLE) {
            if (resultCode > 0) {
                bluetoothStatus = "Vivo terlihat oleh HP lain selama " + resultCode
                        + " detik. Sekarang cari dari Bluetooth Infinix.";
            } else {
                bluetoothStatus = "Mode terlihat ditolak. Infinix mungkin tidak dapat menemukan Vivo.";
            }
            updateStatus();
        }
    }

    private void startBluetoothGamepad(BluetoothDevice device) {
        selectedHidDevice = device;
        if (bluetoothGamepad != null) {
            bluetoothGamepad.switchProfile(device, selectedHidMode);
            bluetoothStatus = "Mengganti ke Bluetooth " + (selectedHidMode == BluetoothHidGamepad.InputMode.GAMEPAD ? "Gamepad" : "Touch HID") + "...";
            if (gamepadStatus != null) gamepadStatus.setText(bluetoothStatus);
            if (device == null) requestBluetoothDiscoverable();
            updateStatus();
            return;
        }
        bluetoothGamepad = new BluetoothHidGamepad(this, message -> runOnUiThread(() -> {
            bluetoothStatus = message;
            if (gamepadStatus != null) gamepadStatus.setText(message);
            if (bluetoothButton != null) {
                bluetoothButton.setText(message.contains("aktif")
                        ? "JARINGAN 2: AKTIF" : "JARINGAN 2: BT");
            }
        }));
        bluetoothStatus = device == null
                ? "Mendaftarkan Bluetooth HID " + (selectedHidMode == BluetoothHidGamepad.InputMode.GAMEPAD ? "Gamepad..." : "Touch HID...")
                : "Bluetooth " + (selectedHidMode == BluetoothHidGamepad.InputMode.GAMEPAD ? "Gamepad" : "Touch HID") + " • Menyambungkan ke " + device.getAddress();
        if (gamepadStatus != null) gamepadStatus.setText(bluetoothStatus);
        if (bluetoothButton != null) bluetoothButton.setText("JARINGAN 2: CONNECTING");
        bluetoothGamepad.setInputMode(selectedHidMode == BluetoothHidGamepad.InputMode.GAMEPAD);
        bluetoothGamepad.start(device, selectedHidMode);
        // A phone acting as a HID peripheral must be discoverable by the Host
        // during first pairing. Android normally keeps phones non-discoverable.
        if (device == null) requestBluetoothDiscoverable();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_BLUETOOTH) {
            boolean granted = grantResults.length > 0;
            for (int result : grantResults) granted &= result == PackageManager.PERMISSION_GRANTED;
            if (granted) showBluetoothDevicePicker();
            else Toast.makeText(this, "Izin Bluetooth diperlukan untuk mode HID", Toast.LENGTH_LONG).show();
        }
    }

    /**
     * Fit the complete decoded frame inside the full-screen Viewer bounds.
     * Aspect ratio is preserved; unused edges remain black instead of cropping content.
     */
    private void setStreamDimensions(StreamConfig config) {
        streamWidth = config.width;
        streamHeight = config.height;
        final int targetOrientation = config.width > config.height
                ? ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                : ActivityInfo.SCREEN_ORIENTATION_PORTRAIT;
        runOnUiThread(() -> {
            // The Viewer follows the Host stream's aspect orientation. This avoids
            // rotating the decoded frame in two separate places on two devices.
            if (getRequestedOrientation() != targetOrientation) {
                setRequestedOrientation(targetOrientation);
            }
            updateVideoSurfaceLayout();
            if (touchOverlay != null) {
                touchOverlay.setSourceAspectRatio(config.width, config.height);
            }
        });
    }

    private void updateVideoSurfaceLayout() {
        if (videoFrame == null || textureView == null) return;
        int viewWidth = videoFrame.getWidth();
        int viewHeight = videoFrame.getHeight();
        if (viewWidth <= 0 || viewHeight <= 0) return;

        if (streamWidth < 2 || streamHeight < 2) {
            textureView.setTransform(new Matrix());
            return;
        }

        // Host orientation is applied when each stream header arrives.
        // Fit the complete frame; never crop its top, bottom or sides.
        float displaySourceWidth = streamWidth;
        float displaySourceHeight = streamHeight;
        float scale = Math.min(
                (float) viewWidth / displaySourceWidth,
                (float) viewHeight / displaySourceHeight);
        float targetWidth = displaySourceWidth * scale;
        float targetHeight = displaySourceHeight * scale;
        float left = (viewWidth - targetWidth) * 0.5f;
        float top = (viewHeight - targetHeight) * 0.5f;
        float right = left + targetWidth;
        float bottom = top + targetHeight;

        // TextureView initially maps its buffer into the View rectangle. Map
        // it into a centered fit rectangle so the entire source stays visible.
        float[] source = {
                0f, 0f,
                viewWidth, 0f,
                viewWidth, viewHeight,
                0f, viewHeight
        };
        float[] destination = {
                left, top,
                right, top,
                right, bottom,
                left, bottom
        };

        Matrix transform = new Matrix();
        if (transform.setPolyToPoly(source, 0, destination, 0, 4)) {
            textureView.setTransform(transform);
        }

    }

    private void receiveLoop(Surface surface, long generation) {
        while (isReceiveActive(generation, surface)) {
            MediaCodec decoder = null;
            DataInputStream input = null;
            Socket socket = new Socket();
            long decodedFrames = 0;
            long receivedBytes = 0;
            long metricWindowStart = SystemClock.elapsedRealtime();
            long metricFramesStart = 0;
            long metricBytesStart = 0;
            try {
                videoSocket = socket;
                socket.setTcpNoDelay(true);
                socket.setKeepAlive(true);
                socket.setReceiveBufferSize(lowLatencyMode ? 96 * 1024 : 256 * 1024);
                // A stalled TCP stream is recovered by reconnecting instead of leaving
                // a black/frozen Viewer on screen indefinitely.
                socket.setSoTimeout(15_000);
                socket.connect(new InetSocketAddress(host, videoPort), 10_000);
                authenticateSocket(socket);
                input = new DataInputStream(
                        new BufferedInputStream(socket.getInputStream(), 32 * 1024));

                StreamConfig config = readStreamHeader(input);
                setStreamDimensions(config);
                decoder = createDecoder(config, surface);
                videoStatus = "Decoder siap " + config.width + " × " + config.height;
                updateStatus();
                if (audioEnabled) startAudioPlayback();

                MediaCodec.BufferInfo outputInfo = new MediaCodec.BufferInfo();
                while (isReceiveActive(generation, surface) && !socket.isClosed()) {
                    int size = input.readInt();
                    if (size == -1) {
                        decoder = releaseDecoder(decoder);
                        config = readStreamHeader(input);
                        setStreamDimensions(config);
                        decoder = createDecoder(config, surface);
                        videoStatus = "Stream diperbarui " + config.width + " × " + config.height;
                        updateStatus();
                        continue;
                    }

                    int flags = input.readInt();
                    long presentationTimeUs = input.readLong();
                    if (size <= 0 || size > 8 * 1024 * 1024) {
                        throw new IllegalStateException("Ukuran paket H.264 tidak valid: " + size);
                    }
                    byte[] packet = new byte[size];
                    input.readFully(packet);
                    receivedBytes += size;
                    if ((flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0
                            && (config.csd0.length > 0 || config.csd1.length > 0)) {
                        continue;
                    }

                    // Never wait for a decoder input slot while leaving decoded output
                    // buffers queued. Some Mali/MediaTek decoders starve in that state.
                    int inputIndex = decoder.dequeueInputBuffer(5_000);
                    while (isReceiveActive(generation, surface) && inputIndex < 0) {
                        decodedFrames += drainDecoderOutputs(decoder, outputInfo, 5_000);
                        inputIndex = decoder.dequeueInputBuffer(5_000);
                    }
                    if (!isReceiveActive(generation, surface) || inputIndex < 0) break;

                    ByteBuffer inputBuffer = decoder.getInputBuffer(inputIndex);
                    if (inputBuffer == null || packet.length > inputBuffer.capacity()) {
                        throw new IllegalStateException("Buffer input decoder terlalu kecil");
                    }
                    inputBuffer.clear();
                    inputBuffer.put(packet);
                    decoder.queueInputBuffer(inputIndex, 0, packet.length,
                            presentationTimeUs, flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM);

                    decodedFrames += drainDecoderOutputs(decoder, outputInfo, 1_000);
                    long metricNow = SystemClock.elapsedRealtime();
                    if (metricNow - metricWindowStart >= 1000L) {
                        long elapsed = Math.max(1L, metricNow - metricWindowStart);
                        float measuredFps = (decodedFrames - metricFramesStart) * 1000f / elapsed;
                        float receiveMbps = (receivedBytes - metricBytesStart) * 8f / elapsed / 1000f;
                        long appRamMb = android.os.Debug.getPss() / 1024L;
                        videoStatus = "Mirroring " + config.width + "×" + config.height
                                + (showPerformance ? String.format(java.util.Locale.US,
                                    " • %.1f FPS • RX %.2f Mbps • RAM %d MB",
                                    measuredFps, receiveMbps, appRamMb) : "");
                        metricWindowStart = metricNow;
                        metricFramesStart = decodedFrames;
                        metricBytesStart = receivedBytes;
                        updateStatus();
                    }
                }
            } catch (Exception error) {
                if (isReceiveActive(generation, surface)) {
                    android.util.Log.w("OfflineMirror", "Viewer video connection will retry", error);
                    videoStatus = "Video terputus • menyambungkan ulang...";
                    updateStatus();
                }
            } finally {
                try { if (input != null) input.close(); } catch (Exception ignored) {}
                try { socket.close(); } catch (Exception ignored) {}
                if (videoSocket == socket) videoSocket = null;
                releaseDecoder(decoder);
            }

            if (isReceiveActive(generation, surface)) {
                try { Thread.sleep(750L); }
                catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        if (generation == receiveGeneration) running = false;
        // The Surface is owned by this receive generation and is released only
        // after its decoder has stopped, never on an ordinary network reconnect.
        try { surface.release(); } catch (Exception ignored) {}
    }

    private boolean isReceiveActive(long generation, Surface surface) {
        return running && generation == receiveGeneration
                && surface != null && surface.isValid();
    }

    private int drainDecoderOutputs(MediaCodec decoder,
                                    MediaCodec.BufferInfo outputInfo,
                                    long firstTimeoutUs) {
        int rendered = 0;
        long timeoutUs = firstTimeoutUs;
        while (true) {
            int outputIndex = decoder.dequeueOutputBuffer(outputInfo, timeoutUs);
            timeoutUs = 0L;
            if (outputIndex >= 0) {
                decoder.releaseOutputBuffer(outputIndex, true);
                rendered++;
            } else if (outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                android.util.Log.i("OfflineMirror",
                        "Decoder output format: " + decoder.getOutputFormat());
            } else if (outputIndex == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED) {
                // Deprecated on modern Android, but harmless for older codec implementations.
            } else {
                break;
            }
        }
        return rendered;
    }

    private void sendTouchContacts(int[] contactIds, float[] xs, float[] ys) {
        if (!controlEnabled || bluetoothGamepad == null) return;
        bluetoothGamepad.sendTouchContacts(contactIds, xs, ys);
    }

    private void toggleLayoutEditMode(Button button) {
        layoutEditMode = !layoutEditMode;
        if (layoutEditMode) {
            gamepadWasHiddenBeforeLayout = gamepadOverlay.getVisibility() != View.VISIBLE;
            gamepadOverlay.setVisibility(View.VISIBLE);
            gamepadOverlay.setLayoutEditMode(true);
            button.setText("SELESAI");
            Toast.makeText(this,
                    "Seret setiap tombol, arah D-pad, atau analog secara terpisah.",
                    Toast.LENGTH_LONG).show();
        } else {
            gamepadOverlay.setLayoutEditMode(false);
            if (gamepadWasHiddenBeforeLayout) gamepadOverlay.setVisibility(View.GONE);
            button.setText("LAYOUT");
            Toast.makeText(this, "Posisi tiap kontrol disimpan.", Toast.LENGTH_SHORT).show();
        }
    }

    private void authenticateSocket(Socket socket) throws Exception {
        if (sessionKey == null || sessionKey.length() < 16) {
            throw new IllegalStateException("Pairing key tidak tersedia. Scan QR Host lagi.");
        }
        DataOutputStream auth = new DataOutputStream(socket.getOutputStream());
        auth.writeUTF(sessionKey);
        auth.flush();
    }

    private void startAudioPlayback() {
        if (audioThread != null && audioThread.isAlive()) return;
        audioThread = new Thread(this::receiveAudio, "OfflineMirror-Audio-Player");
        audioThread.start();
    }

    private void receiveAudio() {
        while (running) {
            AudioTrack track = null;
            DataInputStream input = null;
            Socket socket = new Socket();
            try {
                socket.setTcpNoDelay(true);
                socket.setKeepAlive(true);
                socket.connect(new InetSocketAddress(host, audioPort), 12_000);
                audioSocket = socket;
                authenticateSocket(socket);
                input = new DataInputStream(
                        new BufferedInputStream(socket.getInputStream(), 32 * 1024));

                int sampleRate = input.readInt();
                int channels = input.readInt();
                int encoding = input.readInt();
                if (sampleRate < 8000 || sampleRate > 96000
                        || (channels != 1 && channels != 2)
                        || encoding != AudioFormat.ENCODING_PCM_16BIT) {
                    throw new IllegalStateException("Format audio Host tidak valid");
                }

                int channelMask = channels == 1
                        ? AudioFormat.CHANNEL_OUT_MONO : AudioFormat.CHANNEL_OUT_STEREO;
                int minBuffer = AudioTrack.getMinBufferSize(
                        sampleRate, channelMask, encoding);
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
                    throw new IllegalStateException("Output audio tidak dapat diinisialisasi");
                }
                track.play();
                audioStatus = "Audio: terhubung";
                updateStatus();

                while (running && !socket.isClosed()) {
                    int count = input.readInt();
                    if (count <= 0 || count > 65536 || (count & 1) != 0) {
                        throw new IllegalStateException("Paket audio tidak valid");
                    }
                    byte[] pcm = new byte[count];
                    input.readFully(pcm);
                    int offset = 0;
                    while (offset < count && running) {
                        int written;
                        if (Build.VERSION.SDK_INT >= 23) {
                            written = track.write(pcm, offset, count - offset,
                                    AudioTrack.WRITE_BLOCKING);
                        } else {
                            written = track.write(pcm, offset, count - offset);
                        }
                        if (written < 0) {
                            throw new IllegalStateException("Output audio terhenti");
                        }
                        if (written == 0) {
                            try { Thread.sleep(2L); }
                            catch (InterruptedException interrupted) {
                                Thread.currentThread().interrupt();
                                return;
                            }
                        } else {
                            offset += written;
                        }
                    }
                }
            } catch (Exception error) {
                if (running) {
                    audioStatus = "Audio terputus • mencoba ulang";
                    updateStatus();
                    android.util.Log.w("OfflineMirror", "Viewer audio retry", error);
                }
            } finally {
                if (track != null) {
                    try { track.pause(); } catch (Exception ignored) {}
                    try { track.flush(); } catch (Exception ignored) {}
                    try { track.release(); } catch (Exception ignored) {}
                }
                try { if (input != null) input.close(); } catch (Exception ignored) {}
                try { socket.close(); } catch (Exception ignored) {}
                if (audioSocket == socket) audioSocket = null;
            }
            if (running) {
                try { Thread.sleep(1200L); }
                catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
        audioStatus = "Audio: berhenti";
        updateStatus();
    }

    private StreamConfig readStreamHeader(DataInputStream input) throws Exception {
        int magic = input.readInt();
        if (magic != MAGIC) throw new IllegalStateException("Header stream tidak dikenal");
        int width = input.readInt();
        int height = input.readInt();
        if (width < 2 || height < 2 || width > 4096 || height > 4096) {
            throw new IllegalStateException("Resolusi stream tidak valid");
        }
        byte[] csd0 = readConfig(input);
        byte[] csd1 = readConfig(input);
        if (csd0.length == 0 && csd1.length == 0) {
            throw new IllegalStateException("Konfigurasi H.264 Host kosong");
        }
        return new StreamConfig(width, height, csd0, csd1);
    }

    private MediaCodec createDecoder(StreamConfig config, Surface surface) throws Exception {
        if (surface == null || !surface.isValid()) {
            throw new IllegalStateException("Surface Viewer belum siap");
        }
        MediaFormat format = MediaFormat.createVideoFormat(
                MediaFormat.MIMETYPE_VIDEO_AVC, config.width, config.height);
        if (config.csd0.length > 0) {
            format.setByteBuffer("csd-0", ByteBuffer.wrap(config.csd0));
        }
        if (config.csd1.length > 0) {
            format.setByteBuffer("csd-1", ByteBuffer.wrap(config.csd1));
        }

        MediaCodec decoder = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC);
        try {
            decoder.configure(format, surface, null, 0);
            decoder.start();
            if (Build.VERSION.SDK_INT >= 30 && lowLatencyMode) {
                android.os.Bundle lowLatency = new android.os.Bundle();
                lowLatency.putInt(MediaCodec.PARAMETER_KEY_LOW_LATENCY, 1);
                try {
                    decoder.setParameters(lowLatency);
                    android.util.Log.i("OfflineMirror", "Enabled decoder low-latency mode");
                } catch (Exception unsupported) {
                    android.util.Log.w("OfflineMirror", "Decoder low-latency mode unavailable", unsupported);
                }
            }
            android.util.Log.i("OfflineMirror", "Using decoder " + decoder.getName()
                    + " for " + config.width + "x" + config.height);
            return decoder;
        } catch (Exception error) {
            try { decoder.release(); } catch (Exception ignored) {}
            throw new IllegalStateException("Decoder AVC gagal: " + error.getMessage(), error);
        }
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
            throw new IllegalStateException("Header codec rusak");
        }
        byte[] bytes = new byte[size];
        input.readFully(bytes);
        return bytes;
    }

    private void updateStatus() {
        runOnUiThread(() -> {
            if (status != null) {
                String pingText = pingRttMs >= 0L ? " • Ping " + pingRttMs + " ms" : "";
                status.setText("Viewer: " + viewerAddress + "  |  Host: " + host + "\n"
                        + videoStatus + pingText + "  •  " + audioStatus + "  •  " + controlStatus);
                if (gamepadStatus != null) {
                    gamepadStatus.setText(bluetoothStatus
                            + " • Bluetooth HID: " + (selectedHidMode == BluetoothHidGamepad.InputMode.GAMEPAD ? "Gamepad" : "Touch"));
                }
            }
        });
    }

    private void stopReceiving() {
        running = false;
        receiveGeneration++;
        try { if (videoSocket != null) videoSocket.close(); } catch (Exception ignored) {}
        try { if (audioSocket != null) audioSocket.close(); } catch (Exception ignored) {}
        // Utility socket stays separate from the video stream.
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            enableImmersiveViewer();
        } else {
            if (gamepadOverlay != null) gamepadOverlay.releaseAllInputs();
            if (touchOverlay != null) touchOverlay.cancelTouches();
            if (layoutEditMode) {
                layoutEditMode = false;
                if (layoutToolButton != null) layoutToolButton.setText("LAYOUT");
                if (gamepadWasHiddenBeforeLayout && gamepadOverlay != null) {
                    gamepadOverlay.setVisibility(View.GONE);
                }
            }
            if (gamepadOverlay != null) gamepadOverlay.setLayoutEditMode(false);
        }
    }

    @Override
    protected void onDestroy() {
        controlLoopRunning = false;
        Thread pinger = pingThread;
        if (pinger != null) pinger.interrupt();
        if (gamepadOverlay != null) gamepadOverlay.releaseAllInputs();
        if (touchOverlay != null) touchOverlay.cancelTouches();
        if (bluetoothGamepad != null) bluetoothGamepad.stop();
        if (gamepadOverlay != null) gamepadOverlay.setLayoutEditMode(false);
        stopReceiving();
        controlOutput = null;
        try { if (controlSocket != null) controlSocket.close(); } catch (Exception ignored) {}
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


/**
 * Transparent gesture recorder. It forwards touch paths only on the Host image
 * area, excluding letterbox margins. The class is package-private so it can
 * share ViewerActivity's source file and protocol types.
 */
final class TouchOverlayView extends View {
    interface Listener { void onTouchContacts(int[] contactIds, float[] xs, float[] ys); }
    private static final int MAX_CONTACTS = 1;
    private final RectF content = new RectF();
    private final int[] pointerIds = {-1, -1, -1, -1, -1};
    private final float[] contactX = new float[MAX_CONTACTS];
    private final float[] contactY = new float[MAX_CONTACTS];
    private Listener listener;
    private float aspectRatio;

    TouchOverlayView(android.content.Context context) {
        super(context);
        setBackgroundColor(Color.TRANSPARENT);
        setWillNotDraw(true);
        setClickable(true);
        setContentDescription("Bluetooth HID Touchscreen • satu sentuhan");
    }
    void setListener(Listener value) { listener = value; }
    void setSourceAspectRatio(int width, int height) {
        if (width > 0 && height > 0) { aspectRatio = (float) width / height; updateContent(); }
    }
    private void updateContent() {
        if (getWidth() <= 0 || getHeight() <= 0) return;
        if (aspectRatio <= 0f) content.set(0f, 0f, getWidth(), getHeight());
        else if ((float) getWidth() / getHeight() > aspectRatio) {
            float width = getHeight() * aspectRatio, left = (getWidth() - width) * .5f;
            content.set(left, 0f, left + width, getHeight());
        } else {
            float height = getWidth() / aspectRatio, top = (getHeight() - height) * .5f;
            content.set(0f, top, getWidth(), top + height);
        }
    }
    private int findSlot(int pointerId) {
        for (int i=0;i<MAX_CONTACTS;i++) if(pointerIds[i]==pointerId)return i;
        return -1;
    }
    private int freeSlot() {
        for (int i=0;i<MAX_CONTACTS;i++) if(pointerIds[i]==-1)return i;
        return -1;
    }
    private void mapPoint(float x,float y,int slot) {
        if(slot<0||content.width()<=0||content.height()<=0)return;
        contactX[slot]=Math.max(0f,Math.min(1f,(x-content.left)/content.width()));
        contactY[slot]=Math.max(0f,Math.min(1f,(y-content.top)/content.height()));
    }
    private void emit() {
        if(listener==null)return;
        int count=0;for(int id:pointerIds)if(id!=-1)count++;
        int[] ids=new int[count];float[] xs=new float[count];float[] ys=new float[count];
        int out=0;
        for(int slot=0;slot<MAX_CONTACTS;slot++)if(pointerIds[slot]!=-1){
            ids[out]=slot+1;xs[out]=contactX[slot];ys[out]=contactY[slot];out++;
        }
        listener.onTouchContacts(ids,xs,ys);
    }
    private void begin(MotionEvent e,int index) {
        int id=e.getPointerId(index);
        if(findSlot(id)!=-1||!content.contains(e.getX(index),e.getY(index)))return;
        int slot=freeSlot();if(slot<0)return;
        pointerIds[slot]=id;mapPoint(e.getX(index),e.getY(index),slot);emit();
    }
    private void move(MotionEvent e) {
        boolean changed=false;
        for(int i=0;i<e.getPointerCount();i++){
            int slot=findSlot(e.getPointerId(i));
            if(slot>=0){mapPoint(e.getX(i),e.getY(i),slot);changed=true;}
        }
        if(changed)emit();
    }
    private void finish(int id) {
        int slot=findSlot(id);if(slot<0)return;
        pointerIds[slot]=-1;emit();performClick();
    }
    void cancelTouches() {
        boolean changed=false;
        for(int i=0;i<MAX_CONTACTS;i++)if(pointerIds[i]!=-1){pointerIds[i]=-1;changed=true;}
        if(changed)emit();
    }
    @Override protected void onSizeChanged(int w,int h,int oldW,int oldH) {
        super.onSizeChanged(w,h,oldW,oldH);updateContent();cancelTouches();
    }
    @Override public boolean onTouchEvent(MotionEvent e) {
        int action=e.getActionMasked(),index=e.getActionIndex();
        if(action==MotionEvent.ACTION_DOWN||action==MotionEvent.ACTION_POINTER_DOWN){begin(e,index);return true;}
        if(action==MotionEvent.ACTION_MOVE){move(e);return true;}
        if(action==MotionEvent.ACTION_UP||action==MotionEvent.ACTION_POINTER_UP){finish(e.getPointerId(index));return true;}
        if(action==MotionEvent.ACTION_CANCEL){cancelTouches();return true;}
        return true;
    }
    @Override public boolean performClick(){super.performClick();return true;}
    @Override protected void onDetachedFromWindow(){cancelTouches();super.onDetachedFromWindow();}
}
