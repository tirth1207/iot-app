package com.aquawatch.iot.bluetooth;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

public class BluetoothClassicManager {
    public static final UUID SPP_UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");
    private static final int BUFFER_SIZE = 512;
    private static final int MAX_RETRIES = 5;

    public interface BluetoothEventListener {
        void onConnectionStateChanged(String state, String details);
        void onRecordReceived(ParsedRecord record);
        void onRawMessage(String raw);
    }

    private final BluetoothAdapter bluetoothAdapter;
    private final StreamingSerialParser parser = new StreamingSerialParser();
    private final ExecutorService workerExecutor = Executors.newSingleThreadExecutor();
    private final AtomicBoolean isRunning = new AtomicBoolean(false);

    private volatile BluetoothSocket activeSocket;
    private volatile BluetoothEventListener listener;

    public BluetoothClassicManager() {
        bluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
    }

    public void setListener(BluetoothEventListener listener) {
        this.listener = listener;
    }

    @SuppressLint("MissingPermission")
    public boolean isBluetoothEnabled() {
        return bluetoothAdapter != null && bluetoothAdapter.isEnabled();
    }

    @SuppressLint("MissingPermission")
    public List<BluetoothDevice> getBondedDevices() {
        if (!isBluetoothEnabled()) return Collections.emptyList();
        Set<BluetoothDevice> paired = bluetoothAdapter.getBondedDevices();
        return paired != null ? new ArrayList<>(paired) : Collections.emptyList();
    }

    @SuppressLint("MissingPermission")
    public void connect(final String macAddress) {
        if (bluetoothAdapter == null || !bluetoothAdapter.isEnabled()) {
            notifyState("ERROR", "Bluetooth is not enabled on this device.");
            return;
        }

        disconnect();
        isRunning.set(true);

        workerExecutor.execute(() -> {
            int attempts = 0;
            String lastError = "Unknown Bluetooth error";

            while (isRunning.get() && attempts < MAX_RETRIES) {
                attempts++;
                notifyState("CONNECTING", "Attempt " + attempts + " of " + MAX_RETRIES);

                final BluetoothDevice device;
                try {
                    device = bluetoothAdapter.getRemoteDevice(macAddress);
                } catch (IllegalArgumentException e) {
                    notifyState("ERROR", "Invalid Bluetooth MAC address: " + macAddress);
                    isRunning.set(false);
                    return;
                } catch (SecurityException e) {
                    notifyState("ERROR", "Bluetooth permission denied. Allow Nearby devices permission for AquaWatch.");
                    isRunning.set(false);
                    return;
                }

                BluetoothSocket socket = null;
                try {
                    bluetoothAdapter.cancelDiscovery();

                    // Try the normal authenticated SPP connection first.
                    try {
                        socket = device.createRfcommSocketToServiceRecord(SPP_UUID);
                        socket.connect();
                    } catch (IOException secureError) {
                        closeQuietly(socket);
                        socket = null;
                        if (!isRunning.get()) return;

                        // Some HC-05 firmware/configurations work only with insecure RFCOMM.
                        notifyState("CONNECTING", "Secure connection failed; trying HC-05 compatibility mode.");
                        socket = device.createInsecureRfcommSocketToServiceRecord(SPP_UUID);
                        socket.connect();
                    }

                    if (!isRunning.get()) {
                        closeQuietly(socket);
                        return;
                    }

                    activeSocket = socket;
                    String name = device.getName() != null ? device.getName() : "HC-05";
                    notifyState("CONNECTED", "Connected to " + name + " (" + macAddress + ")");
                    parser.reset();
                    readStream(socket.getInputStream());
                    break;
                } catch (SecurityException e) {
                    closeQuietly(socket);
                    notifyState("ERROR", "Bluetooth permission denied. Allow Nearby devices permission for AquaWatch.");
                    isRunning.set(false);
                    return;
                } catch (IOException e) {
                    lastError = e.getClass().getSimpleName()
                            + (e.getMessage() == null ? "" : ": " + e.getMessage());
                    closeQuietly(socket);
                    activeSocket = null;

                    if (attempts >= MAX_RETRIES) {
                        notifyState("ERROR", "Connection failed after " + MAX_RETRIES + " attempts. Last error: " + lastError);
                        isRunning.set(false);
                        return;
                    }
                    try {
                        Thread.sleep(1500L * attempts);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        isRunning.set(false);
                        return;
                    }
                }
            }
        });
    }

    private void readStream(InputStream in) {
        byte[] buffer = new byte[BUFFER_SIZE];
        try {
            while (isRunning.get()) {
                int bytesRead = in.read(buffer);
                if (bytesRead == -1) break;

                List<ParsedRecord> records = parser.feed(buffer, bytesRead);
                for (ParsedRecord record : records) {
                    BluetoothEventListener current = listener;
                    if (current != null) {
                        current.onRecordReceived(record);
                        if (record instanceof ParsedRecord.Telemetry) {
                            ParsedRecord.Telemetry telemetry = (ParsedRecord.Telemetry) record;
                            current.onRawMessage(telemetry.levelPercent + "% | "
                                    + telemetry.distanceCm + "cm | " + telemetry.status);
                        }
                    }
                }
            }
        } catch (IOException ignored) {
            // The connection-state update below reports the disconnect.
        } finally {
            closeSocket();
            if (isRunning.get()) {
                notifyState("DISCONNECTED", "Bluetooth connection lost while reading serial data.");
            }
        }
    }

    public synchronized void disconnect() {
        isRunning.set(false);
        closeSocket();
        notifyState("DISCONNECTED", "Disconnected");
    }

    private synchronized void closeSocket() {
        BluetoothSocket socket = activeSocket;
        activeSocket = null;
        closeQuietly(socket);
    }

    private static void closeQuietly(BluetoothSocket socket) {
        if (socket != null) {
            try {
                socket.close();
            } catch (IOException ignored) {
                // Best-effort cleanup.
            }
        }
    }

    private void notifyState(String state, String details) {
        BluetoothEventListener current = listener;
        if (current != null) {
            current.onConnectionStateChanged(state, details);
        }
    }
}
