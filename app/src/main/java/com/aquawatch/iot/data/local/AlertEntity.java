package com.aquawatch.iot.data.local;

import androidx.room.Entity;
import androidx.room.PrimaryKey;

@Entity(tableName = "alerts_table")
public class AlertEntity {
    @PrimaryKey(autoGenerate = true)
    public long id;
    public long timestamp;
    public String alertType;
    public String message;
    public float levelPercent;

    public AlertEntity(long timestamp, String alertType, String message, float levelPercent) {
        this.timestamp = timestamp;
        this.alertType = alertType;
        this.message = message;
        this.levelPercent = levelPercent;
    }
}
