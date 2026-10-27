package com.aquawatch.iot.service;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;

import androidx.core.app.NotificationCompat;

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

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class AquaWatchForegroundService extends Service implements BluetoothClassicManager.BluetoothEventListener {

    public static final String ACTION_START = "ACTION_START";
    public static final String ACTION_STOP = "ACTION_STOP";
    public static final String EXTRA_MAC = "EXTRA_MAC";
    public static final String CHANNEL_ID = "aquawatch_service_channel";
    private static final int NOTIF_ID = 1001;

    private AquaWatchApp app;
    private BluetoothClassicManager btManager;
    private AlertNotificationManager alertManager;
    private final ThresholdEvaluator evaluator = new ThresholdEvaluator();
    private final ExecutorService dbExecutor = Executors.newSingleThreadExecutor();
    private final ThingSpeakApiClient apiClient = new ThingSpeakApiClient();

    private long lastUploadTime = 0L;
    private static final long MIN_UPLOAD_INTERVAL_MS = 20000L;

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
        app = (AquaWatchApp) getApplication();
        btManager = app.getBluetoothManager();
        btManager.setListener(this);
        alertManager = new AlertNotificationManager(this);
        createServiceChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_START.equals(intent.getAction())) {
            String mac = intent.getStringExtra(EXTRA_MAC);
            startInForeground("Connecting to Arduino Bluetooth...");
            if (mac != null && !mac.isEmpty()) {
                btManager.connect(mac);
            }
        } else if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            btManager.disconnect();
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf();
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

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);
        } else {
            startForeground(NOTIF_ID, notification);
        }
    }

    private void updateNotification(String text) {
        Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("AquaWatch Continuous Monitoring")
                .setContentText(text)
                .setSmallIcon(R.drawable.ic_tank_indicator)
                .setOngoing(true)
                .build();
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null) {
            nm.notify(NOTIF_ID, notification);
        }
    }

    @Override
    public void onConnectionStateChanged(String state, String details) {
        updateNotification(state + ": " + details);
        Intent broadcast = new Intent("com.aquawatch.STATE_UPDATE");
        broadcast.putExtra("state", state);
        broadcast.putExtra("details", details);
        sendBroadcast(broadcast);
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

            if (eval.triggerAlert) {
                alertManager.postAlert(eval.alertTitle, eval.alertMessage);
            }

            dbExecutor.execute(() -> {
                app.getDatabase().dao().insertReading(new ReadingEntity(now, t.levelPercent, t.distanceCm, eval.status.code));
                if (now - lastUploadTime >= MIN_UPLOAD_INTERVAL_MS && !apiKey.isEmpty()) {
                    ThingSpeakApiClient.Result upResult = apiClient.uploadReading(apiKey, t.levelPercent, t.distanceCm, eval.status.code, now / 1000L);
                    if (upResult.success) {
                        lastUploadTime = now;
                    } else {
                        app.getDatabase().dao().enqueueSync(new SyncQueueEntity(now, t.levelPercent, t.distanceCm, eval.status.code));
                    }
                } else {
                    app.getDatabase().dao().enqueueSync(new SyncQueueEntity(now, t.levelPercent, t.distanceCm, eval.status.code));
                }
            });

            Intent broadcast = new Intent("com.aquawatch.TELEMETRY_UPDATE");
            broadcast.putExtra("level", t.levelPercent);
            broadcast.putExtra("distance", t.distanceCm);
            broadcast.putExtra("statusCode", eval.status.code);
            broadcast.putExtra("statusLabel", eval.status.label);
            sendBroadcast(broadcast);

        } else if (record instanceof ParsedRecord.HardwareError) {
            ThresholdEvaluator.Result eval = evaluator.evaluate(0f, 0f, lowThresh, highThresh, true);
            if (eval.triggerAlert) {
                alertManager.postAlert(eval.alertTitle, eval.alertMessage);
            }
        }
    }

    @Override
    public void onRawMessage(String raw) {
        Intent broadcast = new Intent("com.aquawatch.RAW_MSG");
        broadcast.putExtra("raw", raw);
        sendBroadcast(broadcast);
    }

    private void createServiceChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "AquaWatch Active Link",
                    NotificationManager.IMPORTANCE_LOW
            );
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) {
                nm.createNotificationChannel(channel);
            }
        }
    }

    @Override
    public void onDestroy() {
        dbExecutor.shutdown();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
