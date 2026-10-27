package com.aquawatch.iot.ui;

import android.Manifest;
import android.bluetooth.BluetoothDevice;
import android.content.BroadcastReceiver;
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

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Executors;

public class MainActivity extends AppCompatActivity {
    private static final int REQ_PERMISSIONS = 101;
    private ActivityMainBinding binding;
    private AquaWatchApp app;

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
            } else if ("com.aquawatch.STATE_UPDATE".equals(action)) {
                String state = intent.getStringExtra("state");
                String details = intent.getStringExtra("details");
                binding.tvConnectionTitle.setText(state);
                binding.tvConnectionSub.setText(details);
            } else if ("com.aquawatch.RAW_MSG".equals(action)) {
                String raw = intent.getStringExtra("raw");
                binding.tvRawSerial.setText(raw);
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

        binding.btnConnect.setOnClickListener(v -> showDeviceSelectionDialog());
        binding.btnSettings.setOnClickListener(v -> showSettingsDialog());
        binding.btnClearHistory.setOnClickListener(v -> {
            Executors.newSingleThreadExecutor().execute(() -> {
                app.getDatabase().dao().clearHistory();
                runOnUiThread(() -> Toast.makeText(this, "Local database reset.", Toast.LENGTH_SHORT).show());
            });
        });
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
    }

    @Override
    protected void onPause() {
        super.onPause();
        unregisterReceiver(telemetryReceiver);
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

    private void showDeviceSelectionDialog() {
        if (!app.getBluetoothManager().isBluetoothEnabled()) {
            Toast.makeText(this, "Bluetooth is not enabled. Please enable Bluetooth in Android Settings.", Toast.LENGTH_LONG).show();
            return;
        }

        List<BluetoothDevice> bonded = app.getBluetoothManager().getBondedDevices();
        if (bonded.isEmpty()) {
            Toast.makeText(this, "No paired devices found. Pair HC-05 in Android Settings first.", Toast.LENGTH_LONG).show();
            return;
        }

        List<String> deviceLabels = new ArrayList<>();
        List<String> macAddresses = new ArrayList<>();

        for (BluetoothDevice dev : bonded) {
            try {
                String name = dev.getName() != null ? dev.getName() : "Unknown";
                deviceLabels.add(name + "\n" + dev.getAddress());
                macAddresses.add(dev.getAddress());
            } catch (SecurityException ignored) {}
        }

        new AlertDialog.Builder(this)
                .setTitle("Select Arduino HC-05")
                .setItems(deviceLabels.toArray(new String[0]), (dialog, which) -> {
                    String selectedMac = macAddresses.get(which);
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
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                needed.add(Manifest.permission.ACCESS_FINE_LOCATION);
            }
        } else {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                needed.add(Manifest.permission.ACCESS_FINE_LOCATION);
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                needed.add(Manifest.permission.POST_NOTIFICATIONS);
            }
        }

        if (!needed.isEmpty()) {
            ActivityCompat.requestPermissions(this, needed.toArray(new String[0]), REQ_PERMISSIONS);
        } else {
            // All required permissions already granted - proceed with Bluetooth operations
            app.getBluetoothManager().getBondedDevices(); // ensure BT is accessible
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_PERMISSIONS) {
            boolean allGranted = true;
            for (int result : grantResults) {
                if (result != PackageManager.PERMISSION_GRANTED) {
                    allGranted = false;
                    break;
                }
            }
            if (!allGranted) {
                Toast.makeText(this, "Permissions required for Bluetooth monitoring.", Toast.LENGTH_LONG).show();
            }
        }
    }
}
