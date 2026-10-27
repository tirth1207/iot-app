package com.aquawatch.iot;

import android.app.Application;
import com.aquawatch.iot.bluetooth.BluetoothClassicManager;
import com.aquawatch.iot.data.local.AquaWatchDatabase;
import com.aquawatch.iot.work.ThingSpeakSyncWorker;

public class AquaWatchApp extends Application {
    private BluetoothClassicManager bluetoothManager;
    private AquaWatchDatabase database;

    @Override
    public void onCreate() {
        super.onCreate();
        bluetoothManager = new BluetoothClassicManager();
        database = AquaWatchDatabase.getInstance(this);
        ThingSpeakSyncWorker.schedulePeriodic(this);
    }

    public BluetoothClassicManager getBluetoothManager() {
        return bluetoothManager;
    }

    public AquaWatchDatabase getDatabase() {
        return database;
    }
}
