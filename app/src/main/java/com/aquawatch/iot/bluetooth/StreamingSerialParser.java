package com.aquawatch.iot.bluetooth;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

public class StreamingSerialParser {
    private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
    private static final int MAX_BUFFER_SIZE = 1024;

    public synchronized List<ParsedRecord> feed(byte[] data, int length) {
        List<ParsedRecord> records = new ArrayList<>();
        for (int i = 0; i < length; i++) {
            byte b = data[i];
            if (b == '\n') {
                String rawLine = new String(buffer.toByteArray(), StandardCharsets.UTF_8).trim();
                buffer.reset();
                if (!rawLine.isEmpty()) {
                    records.add(parseLine(rawLine));
                }
            } else {
                if (buffer.size() < MAX_BUFFER_SIZE) {
                    buffer.write(b);
                } else {
                    buffer.reset();
                    records.add(new ParsedRecord.MalformedData("", "Buffer overflow discarded"));
                }
            }
        }
        return records;
    }

    private ParsedRecord parseLine(String line) {
        String cleanLine = line.replace("\r", "");
        String[] tokens = cleanLine.split(",");
        for (int i = 0; i < tokens.length; i++) {
            tokens[i] = tokens[i].trim();
        }

        if (tokens.length == 0 || tokens[0].isEmpty()) {
            return new ParsedRecord.MalformedData(line, "Empty message");
        }

        if ("ERROR".equalsIgnoreCase(tokens[0])) {
            String code = (tokens.length >= 2) ? tokens[1] : "UNKNOWN_ERROR";
            return new ParsedRecord.HardwareError(code);
        }

        if ("ALERT".equalsIgnoreCase(tokens[0])) {
            String alertType = (tokens.length >= 2) ? tokens[1] : "GENERIC_ALERT";
            return new ParsedRecord.DeviceAlert(alertType);
        }

        if (tokens.length != 3) {
            return new ParsedRecord.MalformedData(line, "Expected 3 tokens, found " + tokens.length);
        }

        try {
            float level = Float.parseFloat(tokens[0]);
            float distance = Float.parseFloat(tokens[1]);
            String status = tokens[2];

            if (level < 0.0f || level > 100.0f) {
                return new ParsedRecord.MalformedData(line, "Level percent out of range: " + level);
            }
            if (distance < 0.0f || distance > 500.0f) {
                return new ParsedRecord.MalformedData(line, "Distance out of range: " + distance);
            }

            return new ParsedRecord.Telemetry(level, distance, status);
        } catch (NumberFormatException nfe) {
            return new ParsedRecord.MalformedData(line, "Non-numeric telemetry values");
        }
    }

    public synchronized void reset() {
        buffer.reset();
    }
}
