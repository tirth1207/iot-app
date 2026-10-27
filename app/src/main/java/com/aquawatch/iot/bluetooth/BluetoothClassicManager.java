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

    private BluetoothSocket activeSocket = null;
    private BluetoothEventListener listener;

    public BluetoothClassicManager() {
        this.bluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
    }

    public void setListener(BluetoothEventListener listener) {
        this.listener = listener;
    }

    public boolean isBluetoothEnabled() {
        return bluetoothAdapter != null && bluetoothAdapter.isEnabled();
    }

    @SuppressLint("MissingPermission")
    public List<BluetoothDevice> getBondedDevices() {
        if (!isBluetoothEnabled()) return Collections.emptyList();
        Set<BluetoothDevice> paired = bluetoothAdapter.getBondedDevices();
        return (paired != null) ? new ArrayList<>(paired) : Collections.emptyList();
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
            while (isRunning.get() && attempts < MAX_RETRIES) {
                attempts++;
                notifyState("CONNECTING", "Attempt " + attempts + " of " + MAX_RETRIES);

                BluetoothDevice device;
                try {
                    device = bluetoothAdapter.getRemoteDevice(macAddress);
                } catch (IllegalArgumentException e) {
                    notifyState("ERROR", "Invalid MAC address: " + macAddress);
                    return;
                }

                try {
                    bluetoothAdapter.cancelDiscovery();
                    BluetoothSocket socket = device.createRfcommSocketToServiceRecord(SPP_UUID);
                    socket.connect();
                    activeSocket = socket;

                    String name = device.getName() != null ? device.getName() : "HC-05";
                    notifyState("CONNECTED", name + " (" + macAddress + ")");
                    parser.reset();

                    readStream(socket.getInputStream());
                    break;
                } catch (IOException e) {
                    closeSocket();
                    if (attempts >= MAX_RETRIES) {
                        notifyState("ERROR", "Connection failed after " + MAX_RETRIES + " attempts.");
                        return;
                    }
                    try {
                        Thread.sleep(2000L * attempts);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
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
                    if (listener != null) {
                        listener.onRecordReceived(record);
                        if (record instanceof ParsedRecord.Telemetry) {
                            ParsedRecord.Telemetry t = (ParsedRecord.Telemetry) record;
                            listener.onRawMessage(t.levelPercent + "% | " + t.distanceCm + "cm | " + t.status);
                        }
                    }
                }
            }
        } catch (IOException ignored) {
        } finally {
            closeSocket();
            if (isRunning.get()) {
                notifyState("DISCONNECTED", "Connection lost");
            }
        }
    }

    public synchronized void disconnect() {
        isRunning.set(false);
        closeSocket();
        notifyState("DISCONNECTED", "Disconnected");
    }

    private synchronized void closeSocket() {
        if (activeSocket != null) {
            try {
                activeSocket.close();
            } catch (IOException ignored) {}
            activeSocket = null;
        }
    }

    private void notifyState(String state, String details) {
        if (listener != null) {
            listener.onConnectionStateChanged(state, details);
        }
    }
}
