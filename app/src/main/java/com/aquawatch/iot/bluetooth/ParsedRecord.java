package com.aquawatch.iot.bluetooth;

public abstract class ParsedRecord {
    private ParsedRecord() {}

    public static final class Telemetry extends ParsedRecord {
        public final float levelPercent;
        public final float distanceCm;
        public final String status;

        public Telemetry(float levelPercent, float distanceCm, String status) {
            this.levelPercent = levelPercent;
            this.distanceCm = distanceCm;
            this.status = status;
        }
    }

    public static final class DeviceAlert extends ParsedRecord {
        public final String alertType;

        public DeviceAlert(String alertType) {
            this.alertType = alertType;
        }
    }

    public static final class HardwareError extends ParsedRecord {
        public final String errorCode;

        public HardwareError(String errorCode) {
            this.errorCode = errorCode;
        }
    }

    public static final class MalformedData extends ParsedRecord {
        public final String raw;
        public final String reason;

        public MalformedData(String raw, String reason) {
            this.raw = raw;
            this.reason = reason;
        }
    }
}
