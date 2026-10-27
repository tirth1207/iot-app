package com.aquawatch.iot;

import android.app.Application;
import android.content.SharedPreferences;
import android.util.Log;

import com.aquawatch.iot.bluetooth.BluetoothClassicManager;
import com.aquawatch.iot.data.local.AquaWatchDatabase;
import com.aquawatch.iot.work.ThingSpeakSyncWorker;

import java.io.PrintWriter;
import java.io.StringWriter;

public class AquaWatchApp extends Application {
    public static final String PREFS_NAME = "AquaWatchPrefs";
    public static final String KEY_LAST_CRASH = "last_uncaught_crash";
    private static final String TAG = "AquaWatch";
    private BluetoothClassicManager bluetoothManager;
    private AquaWatchDatabase database;
    private Thread.UncaughtExceptionHandler previousCrashHandler;

    @Override
    public void onCreate() {
        super.onCreate();
        installCrashHandler();
        bluetoothManager = new BluetoothClassicManager();
        database = AquaWatchDatabase.getInstance(this);
        ThingSpeakSyncWorker.schedulePeriodic(this);
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
