package com.aquawatch.iot.ui;

import android.Manifest;
import android.bluetooth.BluetoothDevice;
import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.EditText;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.aquawatch.iot.AquaWatchApp;
import com.aquawatch.iot.R;
import com.aquawatch.iot.databinding.ActivityMainBinding;
import com.aquawatch.iot.service.AquaWatchForegroundService;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Executors;

public class MainActivity extends AppCompatActivity {
    private static final int REQ_PERMISSIONS = 101;
    private static final int MAX_LOG_CHARS = 18000;
    private ActivityMainBinding binding;
    private AquaWatchApp app;
    private final StringBuilder debugLog = new StringBuilder();
    private final SimpleDateFormat logTime = new SimpleDateFormat("HH:mm:ss.SSS", Locale.US);

    private final BroadcastReceiver telemetryReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if ("com.aquawatch.TELEMETRY_UPDATE".equals(action)) {
                float level = intent.getFloatExtra("level", 0f);
                float distance = intent.getFloatExtra("distance", 0f);
                String label = intent.getStringExtra("statusLabel");

                binding.waterTankView.setWaterLevel(level);
                binding.tvLevelPercentage.setText(String.format(Locale.US, "%.0f%%", level));
                binding.tvDistance.setText(String.format(Locale.US, "%.1f cm", distance));
                binding.tvCondition.setText(label);
                appendDebug("TELEMETRY", String.format(Locale.US, "level=%.1f%% distance=%.1fcm status=%s", level, distance, label));
            } else if ("com.aquawatch.STATE_UPDATE".equals(action)) {
                String state = intent.getStringExtra("state");
                String details = intent.getStringExtra("details");
                binding.tvConnectionTitle.setText(state == null ? "Unknown" : state);
                binding.tvConnectionSub.setText(details == null ? "" : details);
                appendDebug(state == null ? "STATE" : state, details == null ? "" : details);
            } else if ("com.aquawatch.RAW_MSG".equals(action)) {
                String raw = intent.getStringExtra("raw");
                binding.tvRawSerial.setText(raw == null ? "" : raw);
                appendDebug("SERIAL", raw == null ? "" : raw);
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityMainBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        app = (AquaWatchApp) getApplication();

        checkAndRequestPermissions();
        appendDebug("APP", "AquaWatch opened. Bluetooth debug console ready.");

        binding.btnConnect.setOnClickListener(v -> showDeviceSelectionDialog());
        binding.btnSettings.setOnClickListener(v -> showSettingsDialog());
        binding.btnClearHistory.setOnClickListener(v -> {
            Executors.newSingleThreadExecutor().execute(() -> {
                app.getDatabase().dao().clearHistory();
                runOnUiThread(() -> Toast.makeText(this, "Local database reset.", Toast.LENGTH_SHORT).show());
            });
        });
        binding.btnCopyLogs.setOnClickListener(v -> {
            String logs = debugLog.toString();
            if (logs.isEmpty()) logs = "No diagnostic events recorded yet.";
            ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            if (clipboard != null) {
                clipboard.setPrimaryClip(ClipData.newPlainText("AquaWatch Bluetooth Debug Logs", logs));
                Toast.makeText(this, "Debug logs copied. Paste them into the chat.", Toast.LENGTH_LONG).show();
            }
        });
        binding.btnClearLogs.setOnClickListener(v -> {
            debugLog.setLength(0);
            binding.tvDebugLog.setText("Logs cleared. Tap Connect to run a fresh diagnostic.");
            appendDebug("APP", "Debug log cleared.");
        });
    }

    private void appendDebug(String category, String message) {
        String line = "[" + logTime.format(new Date()) + "] " + category + ": " + message;
        debugLog.append(line).append('\n');
        if (debugLog.length() > MAX_LOG_CHARS) {
            debugLog.delete(0, debugLog.length() - MAX_LOG_CHARS);
        }
        if (binding != null) {
            binding.tvDebugLog.setText(debugLog.toString());
            binding.tvDebugLog.post(() -> {
                if (binding.tvDebugLog.getLayout() != null) {
                    int scrollAmount = binding.tvDebugLog.getLayout().getLineTop(binding.tvDebugLog.getLineCount()) - binding.tvDebugLog.getHeight();
                    if (scrollAmount > 0) binding.tvDebugLog.scrollTo(0, scrollAmount);
                }
            });
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        IntentFilter filter = new IntentFilter();
        filter.addAction("com.aquawatch.TELEMETRY_UPDATE");
        filter.addAction("com.aquawatch.STATE_UPDATE");
        filter.addAction("com.aquawatch.RAW_MSG");
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(telemetryReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(telemetryReceiver, filter);
        }
        appendDebug("APP", "Main screen resumed; listening for Bluetooth state and serial data.");
    }

    @Override
    protected void onPause() {
        super.onPause();
        try {
            unregisterReceiver(telemetryReceiver);
        } catch (IllegalArgumentException ignored) {
            // Receiver may already have been unregistered.
        }
    }

    private void showSettingsDialog() {
        SharedPreferences prefs = getSharedPreferences("AquaWatchPrefs", MODE_PRIVATE);
        View view = LayoutInflater.from(this).inflate(R.layout.dialog_settings, null);
        EditText etKey = view.findViewById(R.id.etApiKey);
        EditText etLow = view.findViewById(R.id.etLowThresh);
        EditText etHigh = view.findViewById(R.id.etHighThresh);

        etKey.setText(prefs.getString("thingspeak_api_key", ""));
        etLow.setText(String.valueOf(prefs.getFloat("low_thresh", 10.0f)));
        etHigh.setText(String.valueOf(prefs.getFloat("high_thresh", 95.0f)));

        new AlertDialog.Builder(this)
                .setTitle("Configuration")
                .setView(view)
                .setPositiveButton("Save", (d, w) -> {
                    float low = 10f;
                    float high = 95f;
                    try {
                        low = Float.parseFloat(etLow.getText().toString());
                        high = Float.parseFloat(etHigh.getText().toString());
                    } catch (NumberFormatException ignored) {}

                    prefs.edit()
                            .putString("thingspeak_api_key", etKey.getText().toString().trim())
                            .putFloat("low_thresh", low)
                            .putFloat("high_thresh", high)
                            .apply();
                    Toast.makeText(this, "Configuration saved", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private boolean hasBluetoothConnectPermission() {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.S
                || ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED;
    }

    private void showDeviceSelectionDialog() {
        if (!hasBluetoothConnectPermission()) {
            getPreferences(MODE_PRIVATE).edit().putBoolean("open_device_dialog_after_permission", true).apply();
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.BLUETOOTH_CONNECT}, REQ_PERMISSIONS);
            appendDebug("ERROR", "PERMISSION_DENIED: request Nearby devices (BLUETOOTH_CONNECT) before listing paired devices.");
            return;
        }
        if (!app.getBluetoothManager().isBluetoothEnabled()) {
            Toast.makeText(this, "Bluetooth is not enabled. Please enable Bluetooth in Android Settings.", Toast.LENGTH_LONG).show();
            appendDebug("ERROR", "Bluetooth adapter is disabled.");
            return;
        }

        List<BluetoothDevice> bonded = app.getBluetoothManager().getBondedDevices();
        if (!hasBluetoothConnectPermission()) {
            appendDebug("ERROR", "PERMISSION_DENIED: Android denied BLUETOOTH_CONNECT while reading paired devices.");
            return;
        }
        if (bonded.isEmpty()) {
            Toast.makeText(this, "No paired devices found. Pair HC-05 in Android Settings first.", Toast.LENGTH_LONG).show();
            appendDebug("ERROR", "No paired Bluetooth devices returned by Android.");
            return;
        }

        List<String> deviceLabels = new ArrayList<>();
        List<String> macAddresses = new ArrayList<>();

        for (BluetoothDevice dev : bonded) {
            try {
                String name = dev.getName() != null ? dev.getName() : "Unknown";
                deviceLabels.add(name + "\n" + dev.getAddress());
                macAddresses.add(dev.getAddress());
                appendDebug("DEVICE", "Paired device found: " + name + " [" + dev.getAddress() + "]");
            } catch (SecurityException e) {
                appendDebug("ERROR", "Could not read paired device details: " + e);
            }
        }

        new AlertDialog.Builder(this)
                .setTitle("Select Arduino HC-05")
                .setItems(deviceLabels.toArray(new String[0]), (dialog, which) -> {
                    String selectedMac = macAddresses.get(which);
                    appendDebug("APP", "User selected Bluetooth device " + selectedMac);
                    AquaWatchForegroundService.start(MainActivity.this, selectedMac);
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void checkAndRequestPermissions() {
        List<String> needed = new ArrayList<>();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {
                needed.add(Manifest.permission.BLUETOOTH_SCAN);
            }
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                needed.add(Manifest.permission.BLUETOOTH_CONNECT);
            }
        } else {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                needed.add(Manifest.permission.ACCESS_FINE_LOCATION);
            }
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S
                && ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            needed.add(Manifest.permission.ACCESS_FINE_LOCATION);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                needed.add(Manifest.permission.POST_NOTIFICATIONS);
            }
        }

        if (!needed.isEmpty()) {
            ActivityCompat.requestPermissions(this, needed.toArray(new String[0]), REQ_PERMISSIONS);
        } else {
            app.getBluetoothManager().getBondedDevices();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != REQ_PERMISSIONS) return;

        boolean connectGranted = hasBluetoothConnectPermission();
        boolean legacyLocationDenied = Build.VERSION.SDK_INT < Build.VERSION_CODES.S
                && ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED;
        boolean scanDenied = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                && ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED;
        boolean notificationsDenied = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED;

        if (!connectGranted) {
            appendDebug("ERROR", "PERMISSION_DENIED: Nearby devices / BLUETOOTH_CONNECT was denied. Bluetooth connection is blocked.");
            Toast.makeText(this, "Allow Nearby devices permission to connect to HC-05.", Toast.LENGTH_LONG).show();
            getPreferences(MODE_PRIVATE).edit().putBoolean("open_device_dialog_after_permission", false).apply();
            return;
        }
        if (legacyLocationDenied || scanDenied || notificationsDenied) {
            appendDebug("WARN", "Optional permission denied (scan/location/notifications); BLUETOOTH_CONNECT is granted, so connecting remains available.");
        }
        boolean openDialog = getPreferences(MODE_PRIVATE).getBoolean("open_device_dialog_after_permission", false);
        getPreferences(MODE_PRIVATE).edit().putBoolean("open_device_dialog_after_permission", false).apply();
        if (openDialog) showDeviceSelectionDialog();
    }
}
