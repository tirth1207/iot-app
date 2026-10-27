package com.aquawatch.iot.data.local;

import androidx.room.Entity;
import androidx.room.PrimaryKey;

@Entity(tableName = "readings_table")
public class ReadingEntity {
    @PrimaryKey(autoGenerate = true)
    public long id;
    public long timestamp;
    public float levelPercent;
    public float distanceCm;
    public int statusCode;

    public ReadingEntity(long timestamp, float levelPercent, float distanceCm, int statusCode) {
        this.timestamp = timestamp;
        this.levelPercent = levelPercent;
        this.distanceCm = distanceCm;
        this.statusCode = statusCode;
    }
}
