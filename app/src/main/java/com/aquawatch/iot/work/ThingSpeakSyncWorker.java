package com.aquawatch.iot.work;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.NonNull;
import androidx.work.Constraints;
import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.NetworkType;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import com.aquawatch.iot.data.local.AquaWatchDao;
import com.aquawatch.iot.data.local.AquaWatchDatabase;
import com.aquawatch.iot.data.local.SyncQueueEntity;
import com.aquawatch.iot.network.ThingSpeakApiClient;

import java.util.List;
import java.util.concurrent.TimeUnit;

public class ThingSpeakSyncWorker extends Worker {

    public ThingSpeakSyncWorker(@NonNull Context context, @NonNull WorkerParameters workerParams) {
        super(context, workerParams);
    }

    public static void schedulePeriodic(Context context) {
        Constraints constraints = new Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build();

        PeriodicWorkRequest request = new PeriodicWorkRequest.Builder(
                ThingSpeakSyncWorker.class,
                15, TimeUnit.MINUTES
        ).setConstraints(constraints).build();

        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                "ThingSpeakSyncWorker",
                ExistingPeriodicWorkPolicy.KEEP,
                request
        );
    }

    @NonNull
    @Override
    public Result doWork() {
        Context ctx = getApplicationContext();
        SharedPreferences prefs = ctx.getSharedPreferences("AquaWatchPrefs", Context.MODE_PRIVATE);
        String apiKey = prefs.getString("thingspeak_api_key", "");

        if (apiKey.isEmpty()) return Result.failure();

        AquaWatchDao dao = AquaWatchDatabase.getInstance(ctx).dao();
        List<SyncQueueEntity> queue = dao.getPendingSyncItems();
        if (queue.isEmpty()) return Result.success();

        ThingSpeakApiClient client = new ThingSpeakApiClient();
        boolean hasFailure = false;

        for (SyncQueueEntity item : queue) {
            ThingSpeakApiClient.Result result = client.uploadReading(
                    apiKey,
                    item.levelPercent,
                    item.distanceCm,
                    item.statusCode,
                    item.timestamp / 1000L
            );

            if (result.success) {
                dao.deleteSyncItem(item);
            } else if (result.rateLimited) {
                return Result.retry();
            } else {
                if (item.retryCount >= 5) {
                    dao.deleteSyncItem(item);
                } else {
                    dao.incrementRetry(item.id);
                }
                hasFailure = true;
            }
        }

        return hasFailure ? Result.retry() : Result.success();
    }
}
