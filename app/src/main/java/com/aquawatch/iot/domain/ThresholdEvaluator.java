package com.aquawatch.iot.domain;

public class ThresholdEvaluator {
    public enum Status {
        NORMAL(0, "Normal"),
        EMPTY(1, "Low Water Warning"),
        OVERFLOW(2, "High Water Alert"),
        SENSOR_ERROR(3, "Sensor Error");

        public final int code;
        public final String label;

        Status(int code, String label) {
            this.code = code;
            this.label = label;
        }
    }

    public static class Result {
        public final Status status;
        public final boolean triggerAlert;
        public final String alertTitle;
        public final String alertMessage;

        public Result(Status status, boolean triggerAlert, String alertTitle, String alertMessage) {
            this.status = status;
            this.triggerAlert = triggerAlert;
            this.alertTitle = alertTitle;
            this.alertMessage = alertMessage;
        }
    }

    private Status lastStatus = Status.NORMAL;
    private long lastAlertTime = 0L;
    private static final long COOLDOWN_MS = 30 * 60 * 1000L;

    public synchronized Result evaluate(float level, float distance, float lowThreshold, float highThreshold, boolean sensorError) {
        Status currentStatus;
        if (sensorError) {
            currentStatus = Status.SENSOR_ERROR;
        } else if (level <= lowThreshold) {
            currentStatus = Status.EMPTY;
        } else if (level >= highThreshold) {
            currentStatus = Status.OVERFLOW;
        } else {
            currentStatus = Status.NORMAL;
        }

        long now = System.currentTimeMillis();
        boolean isTransition = (currentStatus != lastStatus);
        boolean cooldownExpired = (now - lastAlertTime) > COOLDOWN_MS;

        boolean trigger = false;
        String title = null;
        String message = null;

        if (currentStatus != Status.NORMAL) {
            if (isTransition || cooldownExpired) {
                trigger = true;
                lastAlertTime = now;
                switch (currentStatus) {
                    case EMPTY:
                        title = "LOW WATER WARNING";
                        message = String.format("Water tank is nearly empty. Current level: %.1f%% (%.1f cm).", level, distance);
                        break;
                    case OVERFLOW:
                        title = "HIGH WATER ALERT";
                        message = String.format("Water tank reached high-water mark. Current level: %.1f%% (%.1f cm).", level, distance);
                        break;
                    case SENSOR_ERROR:
                        title = "HARDWARE SENSOR FAILURE";
                        message = "Ultrasonic sensor returned ERROR_NO_ECHO. Check HC-SR04 connections.";
                        break;
                    default:
                        break;
                }
            }
        }

        lastStatus = currentStatus;
        return new Result(currentStatus, trigger, title, message);
    }

    public synchronized void reset() {
        lastStatus = Status.NORMAL;
        lastAlertTime = 0L;
    }
}
