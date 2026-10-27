package com.aquawatch.iot.data.local;

import androidx.lifecycle.LiveData;
import androidx.room.Dao;
import androidx.room.Delete;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;

import java.util.List;

@Dao
public interface AquaWatchDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void insertReading(ReadingEntity reading);

    @Query("SELECT * FROM readings_table ORDER BY timestamp DESC LIMIT 50")
    LiveData<List<ReadingEntity>> getRecentReadings();

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void enqueueSync(SyncQueueEntity syncItem);

    @Query("SELECT * FROM sync_queue_table ORDER BY timestamp ASC LIMIT 10")
    List<SyncQueueEntity> getPendingSyncItems();

    @Delete
    void deleteSyncItem(SyncQueueEntity item);

    @Query("UPDATE sync_queue_table SET retryCount = retryCount + 1 WHERE id = :id")
    void incrementRetry(long id);

    @Query("SELECT COUNT(*) FROM sync_queue_table")
    LiveData<Integer> getPendingCount();

    @Query("DELETE FROM readings_table")
    void clearHistory();
}
