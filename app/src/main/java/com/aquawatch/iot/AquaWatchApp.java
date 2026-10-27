package com.aquawatch.iot;

import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import com.aquawatch.iot.bluetooth.BluetoothClassicManager;
import com.aquawatch.iot.data.local.AquaWatchDatabase;
import com.aquawatch.iot.work.ThingSpeakSyncWorker;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

public class AquaWatchApp extends Application {
    public static final String PREFS_NAME = "AquaWatchPrefs";
    public static final String KEY_LAST_CRASH = "last_uncaught_crash";
    private static final String KEY_BREADCRUMBS = "diagnostic_breadcrumbs";
    private static final String TAG = "AquaWatch";
    private static final int MAX_BREADCRUMBS = 50;
    private static final ArrayDeque<String> BREADCRUMBS = new ArrayDeque<>();
    private static final Object BREADCRUMB_LOCK = new Object();

    private BluetoothClassicManager bluetoothManager;
    private AquaWatchDatabase database;
    private Thread.UncaughtExceptionHandler previousCrashHandler;

    @Override
    public void onCreate() {
        super.onCreate();
        installCrashHandler();
        loadBreadcrumbs(this);
        bluetoothManager = new BluetoothClassicManager();
        database = AquaWatchDatabase.getInstance(this);
        ThingSpeakSyncWorker.schedulePeriodic(this);
    }

    public static void recordBreadcrumb(Context context, String breadcrumb) {
        String line = System.currentTimeMillis() + " " + breadcrumb;
        synchronized (BREADCRUMB_LOCK) {
            BREADCRUMBS.addLast(line);
            while (BREADCRUMBS.size() > MAX_BREADCRUMBS) BREADCRUMBS.removeFirst();
            StringBuilder serialized = new StringBuilder();
            for (String item : BREADCRUMBS) serialized.append(item).append("\n");
            try {
                context.getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
                        .putString(KEY_BREADCRUMBS, serialized.toString()).apply();
            } catch (Throwable error) {
                Log.e(TAG, "Could not persist diagnostic breadcrumb", error);
            }
        }
    }

    private static void loadBreadcrumbs(Context context) {
        synchronized (BREADCRUMB_LOCK) {
            if (!BREADCRUMBS.isEmpty()) return;
            String serialized = context.getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                    .getString(KEY_BREADCRUMBS, "");
            if (serialized == null || serialized.isEmpty()) return;
            for (String line : serialized.split("\\n")) {
                if (!line.isEmpty()) BREADCRUMBS.addLast(line);
            }
            while (BREADCRUMBS.size() > MAX_BREADCRUMBS) BREADCRUMBS.removeFirst();
        }
    }

    public static List<String> getBreadcrumbs(Context context) {
        loadBreadcrumbs(context.getApplicationContext());
        synchronized (BREADCRUMB_LOCK) {
            return new ArrayList<>(BREADCRUMBS);
        }
    }

    public static String consumeLastCrash(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        String crash = prefs.getString(KEY_LAST_CRASH, null);
        if (crash != null) prefs.edit().remove(KEY_LAST_CRASH).apply();
        return crash;
    }

    private void installCrashHandler() {
        previousCrashHandler = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, error) -> {
            try {
                StringWriter writer = new StringWriter();
                error.printStackTrace(new PrintWriter(writer));
                getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
                        .putString(KEY_LAST_CRASH, "Thread " + thread.getName() + ":\n" + writer)
                        .commit();
                Log.e(TAG, "Saved uncaught crash from " + thread.getName(), error);
            } catch (Throwable saveFailure) {
                Log.e(TAG, "Could not persist uncaught crash", saveFailure);
            } finally {
                if (previousCrashHandler != null) {
                    previousCrashHandler.uncaughtException(thread, error);
                } else {
                    android.os.Process.killProcess(android.os.Process.myPid());
                    System.exit(10);
                }
            }
        });
    }

    public BluetoothClassicManager getBluetoothManager() {
        return bluetoothManager;
    }

    public AquaWatchDatabase getDatabase() {
        return database;
    }
}
