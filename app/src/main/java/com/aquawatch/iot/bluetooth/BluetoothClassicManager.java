package com.aquawatch.iot.bluetooth;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.util.Log;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Future;

public class BluetoothClassicManager {
    public static final UUID SPP_UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");
    private static final int BUFFER_SIZE = 512;
    private static final int MAX_RETRIES = 3;
    private static final String TAG = "AquaWatchBT";

    public interface BluetoothEventListener {
        void onConnectionStateChanged(String state, String details);
        void onRecordReceived(ParsedRecord record);
        void onRawMessage(String raw);
    }

    private final BluetoothAdapter bluetoothAdapter;
    private final StreamingSerialParser parser = new StreamingSerialParser();
    private volatile ExecutorService workerExecutor = Executors.newSingleThreadExecutor();
    private final AtomicInteger generation = new AtomicInteger(0);
    private final CopyOnWriteArrayList<BluetoothEventListener> listeners = new CopyOnWriteArrayList<>();
    private volatile Future<?> connectionTask;
    private volatile BluetoothSocket activeSocket;
    private volatile BluetoothSocket connectingSocket;

    public BluetoothClassicManager() {
        bluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
    }

    public void setListener(BluetoothEventListener listener) { addListener(listener); }

    public void addListener(BluetoothEventListener listener) {
        if (listener != null && !listeners.contains(listener)) listeners.add(listener);
    }

    public void removeListener(BluetoothEventListener listener) { listeners.remove(listener); }

    @SuppressLint("MissingPermission")
    public boolean isBluetoothEnabled() {
        if (bluetoothAdapter == null) return false;
        try { return bluetoothAdapter.isEnabled(); }
        catch (SecurityException e) { notifyState("ERROR", "PERMISSION_DENIED: BLUETOOTH_CONNECT is required. " + messageOf(e)); return false; }
    }

    @SuppressLint("MissingPermission")
    public List<BluetoothDevice> getBondedDevices() {
        try {
            if (bluetoothAdapter == null || !bluetoothAdapter.isEnabled()) return Collections.emptyList();
            Set<BluetoothDevice> paired = bluetoothAdapter.getBondedDevices();
            return paired != null ? new ArrayList<>(paired) : Collections.emptyList();
        } catch (SecurityException e) {
            notifyState("ERROR", "PERMISSION_DENIED: BLUETOOTH_CONNECT is required to list paired devices. " + messageOf(e));
            return Collections.emptyList();
        }
    }

    @SuppressLint("MissingPermission")
    public void connect(final String macAddress) {
        Log.d(TAG, "BT: connect() called");
        try {
            if (bluetoothAdapter == null || !isBluetoothEnabled()) {
                notifyState("ERROR", "Bluetooth is disabled or unavailable.");
                return;
            }
            disconnectInternal(false);
            final int taskGeneration = generation.incrementAndGet();
            workerExecutor.shutdownNow();
            workerExecutor = Executors.newSingleThreadExecutor();
            connectionTask = workerExecutor.submit(() -> {
                BluetoothDevice device;
                try {
                    device = bluetoothAdapter.getRemoteDevice(macAddress);
                    int bondState = device.getBondState();
                    Log.d(TAG, "BT: bond state = " + bondState);
                    if (bondState != BluetoothDevice.BOND_BONDED) {
                        notifyState("ERROR", "NOT_BONDED: Pair HC-05 in Android Settings first (PIN 1234 or 0000). Bond state = " + bondState);
                        return;
                    }
                } catch (SecurityException e) {
                    notifyState("ERROR", "PERMISSION_DENIED: grant Nearby devices / BLUETOOTH_CONNECT permission. " + messageOf(e));
                    return;
                } catch (IllegalArgumentException e) {
                    notifyState("ERROR", "Invalid Bluetooth MAC address: " + macAddress);
                    return;
                } catch (Throwable t) {
                    reportThrowable("BT: worker failed before socket creation", t);
                    return;
                }

                for (int attempt = 1; attempt <= MAX_RETRIES && isCurrent(taskGeneration); attempt++) {
                    BluetoothSocket socket = null;
                    try {
                        notifyState("CONNECTING", "Attempt " + attempt + " of " + MAX_RETRIES + " to " + safeName(device));
                        bluetoothAdapter.cancelDiscovery();
                        socket = connectWithFallbacks(device, taskGeneration);
                        if (!isCurrent(taskGeneration)) { closeQuietly(socket); return; }
                        activeSocket = socket;
                        connectingSocket = null;
                        parser.reset();
                        notifyState("CONNECTED", "Connected to " + safeName(device) + " (" + macAddress + "); waiting for serial data.");
                        readStream(socket.getInputStream(), taskGeneration);
                        return;
                    } catch (SecurityException e) {
                        closeQuietly(socket);
                        notifyState("ERROR", "PERMISSION_DENIED: BLUETOOTH_CONNECT was denied or revoked. " + messageOf(e));
                        return;
                    } catch (IOException e) {
                        closeQuietly(socket);
                        if (connectingSocket == socket) connectingSocket = null;
                        activeSocket = null;
                        if (!isCurrent(taskGeneration)) return;
                        if (attempt == MAX_RETRIES) {
                            notifyState("ERROR", "RFCOMM connect failed (" + messageOf(e) + "). Failed after " + MAX_RETRIES + " attempts.");
                            return;
                        }
                        try { Thread.sleep(400L); }
                        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); return; }
                    } catch (Throwable t) {
                        closeQuietly(socket);
                        reportThrowable("BT: connection attempt " + attempt + " crashed", t);
                        return;
                    } finally {
                        if (connectingSocket == socket) connectingSocket = null;
                    }
                }
            });
            Log.d(TAG, "BT: worker thread started");
        } catch (Throwable t) {
            reportThrowable("BT: connect() failed synchronously", t);
        }
    }

    private void reportThrowable(String context, Throwable throwable) {
        Log.e(TAG, context, throwable);
        java.io.StringWriter sw = new java.io.StringWriter();
        throwable.printStackTrace(new java.io.PrintWriter(sw));
        notifyState("ERROR", context + ": " + throwable + "\n" + sw);
    }

    @SuppressLint("MissingPermission")
    private BluetoothSocket connectWithFallbacks(BluetoothDevice device, int taskGeneration) throws IOException {
        List<String> errors = new ArrayList<>();
        // HC-05 commonly works best with insecure SPP, then RFCOMM channel 1.
        try {
            BluetoothSocket socket = device.createInsecureRfcommSocketToServiceRecord(SPP_UUID);
            connectingSocket = socket;
            bluetoothAdapter.cancelDiscovery();
            socket.connect();
            return socket;
        } catch (IOException | SecurityException e) {
            errors.add("insecure SPP: " + messageOf(e));
            closeQuietly(connectingSocket); connectingSocket = null;
            if (e instanceof SecurityException) throw (SecurityException)e;
        }
        if (!isCurrent(taskGeneration)) throw new IOException("Connection cancelled");
        notifyState("CONNECTING", "Insecure SPP failed (" + errors.get(errors.size()-1) + "); trying channel 1.");
        try {
            Method method = device.getClass().getMethod("createRfcommSocket", int.class);
            BluetoothSocket socket = (BluetoothSocket) method.invoke(device, 1);
            connectingSocket = socket;
            bluetoothAdapter.cancelDiscovery();
            socket.connect();
            return socket;
        } catch (NoSuchMethodException | IllegalAccessException | InvocationTargetException | IOException | SecurityException e) {
            Throwable detail = e instanceof InvocationTargetException && ((InvocationTargetException)e).getCause() != null
                    ? ((InvocationTargetException)e).getCause() : e;
            errors.add("RFCOMM channel 1: " + messageOf(detail));
            closeQuietly(connectingSocket); connectingSocket = null;
            if (detail instanceof SecurityException) throw (SecurityException)detail;
        }
        if (!isCurrent(taskGeneration)) throw new IOException("Connection cancelled");
        notifyState("CONNECTING", "Channel 1 failed (" + errors.get(errors.size()-1) + "); trying secure SPP.");
        try {
            BluetoothSocket socket = device.createRfcommSocketToServiceRecord(SPP_UUID);
            connectingSocket = socket;
            bluetoothAdapter.cancelDiscovery();
            socket.connect();
            return socket;
        } catch (IOException | SecurityException e) {
            errors.add("secure SPP: " + messageOf(e));
            closeQuietly(connectingSocket); connectingSocket = null;
            if (e instanceof SecurityException) throw (SecurityException)e;
        }
        throw new IOException("All RFCOMM strategies failed: " + String.join(" | ", errors));
    }

    @SuppressLint("MissingPermission")
    private String safeName(BluetoothDevice device) {
        try { String name = device.getName(); return name == null ? "HC-05" : name; }
        catch (SecurityException e) { notifyState("ERROR", "PERMISSION_DENIED reading device name: " + messageOf(e)); return "Bluetooth device"; }
    }

    private boolean isCurrent(int taskGeneration) {
        return generation.get() == taskGeneration && !Thread.currentThread().isInterrupted();
    }

    private static String messageOf(Throwable error) {
        if (error == null) return "unknown error";
        return error.getClass().getSimpleName()
                + (error.getMessage() == null ? "" : ": " + error.getMessage());
    }

    private void readStream(InputStream in, int taskGeneration) {
        byte[] buffer = new byte[BUFFER_SIZE];
        boolean receivedAny = false;
        try {
            while (isCurrent(taskGeneration)) {
                int bytesRead = in.read(buffer);
                if (bytesRead < 0) break;
                if (bytesRead == 0) continue;
                receivedAny = true;
                List<ParsedRecord> records = parser.feed(buffer, bytesRead);
                for (ParsedRecord record : records) {
                    for (BluetoothEventListener current : listeners) {
                        current.onRecordReceived(record);
                        if (record instanceof ParsedRecord.Telemetry) {
                            ParsedRecord.Telemetry t = (ParsedRecord.Telemetry) record;
                            current.onRawMessage(t.levelPercent + "% | " + t.distanceCm + "cm | " + t.status);
                        }
                    }
                }
            }
        } catch (IOException e) {
            if (isCurrent(taskGeneration)) notifyState("DISCONNECTED", "RFCOMM stream read failed: " + messageOf(e));
        } catch (Throwable t) {
            reportThrowable("BT: serial reader crashed", t);
        } finally {
            if (isCurrent(taskGeneration)) {
                closeQuietly(activeSocket); activeSocket = null;
                if (!receivedAny) notifyState("DISCONNECTED", "Connected but no data: no serial bytes received. Check Arduino TX -> HC-05 RX and 9600 baud.");
            }
        }
    }

    public synchronized void disconnect() { disconnectInternal(true); }

    private synchronized void disconnectInternal(boolean notify) {
        generation.incrementAndGet();
        Future<?> task = connectionTask; connectionTask = null;
        if (task != null) task.cancel(true);
        closeQuietly(connectingSocket); connectingSocket = null;
        closeQuietly(activeSocket); activeSocket = null;
        if (notify) notifyState("DISCONNECTED", "Disconnected by user.");
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
        Log.d(TAG, "BT STATE " + state + ": " + details);
        for (BluetoothEventListener current : listeners) {
            try {
                current.onConnectionStateChanged(state, details);
            } catch (Throwable listenerError) {
                Log.e(TAG, "A Bluetooth state listener threw", listenerError);
            }
        }
    }
}
