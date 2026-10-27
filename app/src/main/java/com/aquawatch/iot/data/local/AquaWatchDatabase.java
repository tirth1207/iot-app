package com.aquawatch.iot.data.local;

import android.content.Context;

import androidx.room.Database;
import androidx.room.Room;
import androidx.room.RoomDatabase;

@Database(entities = {ReadingEntity.class, SyncQueueEntity.class, AlertEntity.class}, version = 1, exportSchema = false)
public abstract class AquaWatchDatabase extends RoomDatabase {
    public abstract AquaWatchDao dao();

    private static volatile AquaWatchDatabase INSTANCE;

    public static AquaWatchDatabase getInstance(Context context) {
        if (INSTANCE == null) {
            synchronized (AquaWatchDatabase.class) {
                if (INSTANCE == null) {
                    INSTANCE = Room.databaseBuilder(
                            context.getApplicationContext(),
                            AquaWatchDatabase.class,
                            "aquawatch_db"
                    ).fallbackToDestructiveMigration().build();
                }
            }
        }
        return INSTANCE;
    }
}
