package com.aquawatch.iot.service;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;

import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;

import com.aquawatch.iot.AquaWatchApp;
import com.aquawatch.iot.R;
import com.aquawatch.iot.bluetooth.BluetoothClassicManager;
import com.aquawatch.iot.bluetooth.ParsedRecord;
import com.aquawatch.iot.data.local.ReadingEntity;
import com.aquawatch.iot.data.local.SyncQueueEntity;
import com.aquawatch.iot.domain.ThresholdEvaluator;
import com.aquawatch.iot.network.ThingSpeakApiClient;
import com.aquawatch.iot.notification.AlertNotificationManager;
import com.aquawatch.iot.ui.MainActivity;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class AquaWatchForegroundService extends Service implements BluetoothClassicManager.BluetoothEventListener {
    public static final String ACTION_START = "ACTION_START";
    public static final String ACTION_STOP = "ACTION_STOP";
    public static final String EXTRA_MAC = "EXTRA_MAC";
    public static final String CHANNEL_ID = "aquawatch_service_channel";
    private static final int NOTIF_ID = 1001;
    private static final String TAG = "AquaWatchService";

    private AquaWatchApp app;
    private BluetoothClassicManager btManager;
    private AlertNotificationManager alertManager;
    private final ThresholdEvaluator evaluator = new ThresholdEvaluator();
    private final ExecutorService dbExecutor = Executors.newSingleThreadExecutor();
    private final ThingSpeakApiClient apiClient = new ThingSpeakApiClient();

    private long lastUploadTime = 0L;
    private static final long MIN_UPLOAD_INTERVAL_MS = 20000L;
    private static final int MAX_AUTO_RECONNECTS = 3;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private String currentMac;
    private int reconnectAttempts = 0;
    private boolean userStopped = false;
    private boolean reconnectScheduled = false;

    public static void start(Context ctx, String mac) {
        Intent intent = new Intent(ctx, AquaWatchForegroundService.class);
        intent.setAction(ACTION_START);
        intent.putExtra(EXTRA_MAC, mac);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            ctx.startForegroundService(intent);
        } else {
            ctx.startService(intent);
        }
    }

    public static void stop(Context ctx) {
        Intent intent = new Intent(ctx, AquaWatchForegroundService.class);
        intent.setAction(ACTION_STOP);
        ctx.startService(intent);
    }

    @Override
    public void onCreate() {
        super.onCreate();
        logBreadcrumb("SERVICE: created");
        try {
            app = (AquaWatchApp) getApplication();
            btManager = app.getBluetoothManager();
            // Register before connect() can be called so CONNECTING is never missed.
            btManager.addListener(this);
            alertManager = new AlertNotificationManager(this);
            createServiceChannel();
        } catch (Throwable t) {
            reportError("SERVICE onCreate failed", t);
            stopSelf();
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? "null" : String.valueOf(intent.getAction());
        String mac = intent == null ? null : intent.getStringExtra(EXTRA_MAC);
        logBreadcrumb("SERVICE: onStartCommand action=" + action + " mac=" + mac);
        try {
            if (intent != null && ACTION_START.equals(intent.getAction())) {
                currentMac = mac;
                reconnectAttempts = 0;
                userStopped = false;
                if (mac == null || mac.trim().isEmpty()) {
                    broadcastState("ERROR", "Invalid or missing Bluetooth MAC address.");
                    stopSelf();
                    return START_NOT_STICKY;
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                        && ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                    broadcastState("ERROR", "PERMISSION_DENIED: grant Nearby devices permission before starting monitoring.");
                    stopSelf();
                    return START_NOT_STICKY;
                }
                try {
                    startInForeground("Connecting to Arduino Bluetooth...");
                } catch (IllegalStateException e) {
                    reportError("Foreground start rejected by Android", e);
                    stopSelf();
                    return START_NOT_STICKY;
                } catch (SecurityException e) {
                    reportError("Foreground start permission failure", e);
                    stopSelf();
                    return START_NOT_STICKY;
                }
                logBreadcrumb("SERVICE: immediately before btManager.connect()");
                btManager.connect(mac);
                logBreadcrumb("SERVICE: immediately after btManager.connect()");
            } else if (intent != null && ACTION_STOP.equals(intent.getAction())) {
                userStopped = true;
                reconnectScheduled = false;
                mainHandler.removeCallbacksAndMessages(null);
                if (btManager != null) btManager.disconnect();
                stopForeground(STOP_FOREGROUND_REMOVE);
                stopSelf();
            } else {
                logBreadcrumb("SERVICE: no start/stop action; no connection initiated");
            }
        } catch (Throwable t) {
            reportError("SERVICE onStartCommand failed", t);
            stopSelf();
            return START_NOT_STICKY;
        }
        return START_NOT_STICKY;
    }

    private void startInForeground(String text) {
        Intent intent = new Intent(this, MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE);
        Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("AquaWatch Continuous Monitoring")
                .setContentText(text)
                .setSmallIcon(R.drawable.ic_tank_indicator)
                .setContentIntent(pi)
                .setOngoing(true)
                .build();
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);
            } else {
                startForeground(NOTIF_ID, notification);
            }
            logBreadcrumb("SERVICE: foreground started");
        } catch (IllegalStateException | SecurityException t) {
            // Includes ForegroundServiceStartNotAllowedException (API 31+) and
            // MissingForegroundServiceTypeException (API 34+), which are IllegalStateException subclasses.
            reportError("startForeground failed (" + t.getClass().getSimpleName() + ")", t);
            throw t;
        } catch (Throwable t) {
            reportError("startForeground failed unexpectedly", t);
            throw t;
        }
    }

    private void reportError(String context, Throwable throwable) {
        Log.e(TAG, context, throwable);
        StringWriter sw = new StringWriter();
        throwable.printStackTrace(new PrintWriter(sw));
        String trace = sw.toString();
        logBreadcrumb("ERROR: " + context + ": " + throwable + "\n" + trace);
        broadcastState("ERROR", context + ": " + throwable + "\n" + trace);
    }

    private void logBreadcrumb(String message) {
        AquaWatchApp.recordBreadcrumb(getApplicationContext(), message);
        Log.d(TAG, message);
    }

    private void updateNotification(String text) {
        Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("AquaWatch Continuous Monitoring")
                .setContentText(text)
                .setSmallIcon(R.drawable.ic_tank_indicator)
                .setOngoing(true)
                .build();
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null) nm.notify(NOTIF_ID, notification);
    }

    @Override
    public void onConnectionStateChanged(String state, String details) {
        mainHandler.post(() -> {
            try {
                updateNotification(state + ": " + details);
                broadcastState(state, details);
                if ("CONNECTED".equals(state)) {
                    reconnectAttempts = 0;
                    reconnectScheduled = false;
                } else if ("DISCONNECTED".equals(state) && !userStopped && currentMac != null
                        && reconnectAttempts < MAX_AUTO_RECONNECTS && !reconnectScheduled) {
                    reconnectAttempts++;
                    reconnectScheduled = true;
                    long delay = 1000L * reconnectAttempts;
                    broadcastState("RECONNECTING", "Connection dropped; retry " + reconnectAttempts + " of " + MAX_AUTO_RECONNECTS + " in " + delay + " ms.");
                    mainHandler.postDelayed(() -> {
                        reconnectScheduled = false;
                        if (!userStopped && currentMac != null) btManager.connect(currentMac);
                    }, delay);
                }
            } catch (Throwable t) {
                reportError("Handling Bluetooth state " + state + " failed", t);
            }
        });
    }

    private void broadcastState(String state, String details) {
        Intent broadcast = new Intent("com.aquawatch.STATE_UPDATE");
        broadcast.setPackage(getPackageName());
        broadcast.putExtra("state", state);
        broadcast.putExtra("details", details);
        sendBroadcast(broadcast);
        logBreadcrumb("STATE: " + state + ": " + details);
    }

    @Override
    public void onRecordReceived(ParsedRecord record) {
        long now = System.currentTimeMillis();
        SharedPreferences prefs = getSharedPreferences("AquaWatchPrefs", MODE_PRIVATE);
        float lowThresh = prefs.getFloat("low_thresh", 10.0f);
        float highThresh = prefs.getFloat("high_thresh", 95.0f);
        String apiKey = prefs.getString("thingspeak_api_key", "");

        if (record instanceof ParsedRecord.Telemetry) {
            ParsedRecord.Telemetry t = (ParsedRecord.Telemetry) record;
            ThresholdEvaluator.Result eval = evaluator.evaluate(t.levelPercent, t.distanceCm, lowThresh, highThresh, false);
            if (eval.triggerAlert) alertManager.postAlert(eval.alertTitle, eval.alertMessage);

            dbExecutor.execute(() -> {
                app.getDatabase().dao().insertReading(new ReadingEntity(now, t.levelPercent, t.distanceCm, eval.status.code));
                if (now - lastUploadTime >= MIN_UPLOAD_INTERVAL_MS && !apiKey.isEmpty()) {
                    ThingSpeakApiClient.Result upResult = apiClient.uploadReading(apiKey, t.levelPercent, t.distanceCm, eval.status.code, now / 1000L);
                    if (upResult.success) lastUploadTime = now;
                    else app.getDatabase().dao().enqueueSync(new SyncQueueEntity(now, t.levelPercent, t.distanceCm, eval.status.code));
                } else {
                    app.getDatabase().dao().enqueueSync(new SyncQueueEntity(now, t.levelPercent, t.distanceCm, eval.status.code));
                }
            });

            Intent broadcast = new Intent("com.aquawatch.TELEMETRY_UPDATE");
            broadcast.setPackage(getPackageName());
            broadcast.putExtra("level", t.levelPercent);
            broadcast.putExtra("distance", t.distanceCm);
            broadcast.putExtra("statusCode", eval.status.code);
            broadcast.putExtra("statusLabel", eval.status.label);
            sendBroadcast(broadcast);
        } else if (record instanceof ParsedRecord.HardwareError) {
            ThresholdEvaluator.Result eval = evaluator.evaluate(0f, 0f, lowThresh, highThresh, true);
            if (eval.triggerAlert) alertManager.postAlert(eval.alertTitle, eval.alertMessage);
        }
    }

    @Override
    public void onRawMessage(String raw) {
        Intent broadcast = new Intent("com.aquawatch.RAW_MSG");
        broadcast.setPackage(getPackageName());
        broadcast.putExtra("raw", raw);
        sendBroadcast(broadcast);
    }

    private void createServiceChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID, "AquaWatch Active Link", NotificationManager.IMPORTANCE_LOW);
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) nm.createNotificationChannel(channel);
        }
    }

    @Override
    public void onDestroy() {
        userStopped = true;
        mainHandler.removeCallbacksAndMessages(null);
        if (btManager != null) btManager.removeListener(this);
        dbExecutor.shutdown();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }
}
