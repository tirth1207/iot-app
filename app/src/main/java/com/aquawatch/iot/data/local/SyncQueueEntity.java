package com.aquawatch.iot.data.local;

import androidx.room.Entity;
import androidx.room.PrimaryKey;

@Entity(tableName = "sync_queue_table")
public class SyncQueueEntity {
    @PrimaryKey(autoGenerate = true)
    public long id;
    public long timestamp;
    public float levelPercent;
    public float distanceCm;
    public int statusCode;
    public int retryCount;

    public SyncQueueEntity(long timestamp, float levelPercent, float distanceCm, int statusCode) {
        this.timestamp = timestamp;
        this.levelPercent = levelPercent;
        this.distanceCm = distanceCm;
        this.statusCode = statusCode;
        this.retryCount = 0;
    }
}
