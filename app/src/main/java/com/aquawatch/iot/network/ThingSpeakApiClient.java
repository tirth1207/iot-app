package com.aquawatch.iot.network;

import java.io.IOException;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

import okhttp3.FormBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

public class ThingSpeakApiClient {
    private static final String UPDATE_URL = "https://api.thingspeak.com/update.json";
    private final OkHttpClient client;

    public static class Result {
        public final boolean success;
        public final boolean rateLimited;
        public final String message;
        public final long entryId;

        public Result(boolean success, boolean rateLimited, String message, long entryId) {
            this.success = success;
            this.rateLimited = rateLimited;
            this.message = message;
            this.entryId = entryId;
        }
    }

    public ThingSpeakApiClient() {
        this.client = new OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(10, TimeUnit.SECONDS)
                .build();
    }

    public Result uploadReading(String apiKey, float level, float distance, int statusCode, long epochSec) {
        if (apiKey == null || apiKey.trim().isEmpty()) {
            return new Result(false, false, "API Key is empty", 0);
        }

        FormBody body = new FormBody.Builder()
                .add("api_key", apiKey)
                .add("field1", String.format(Locale.US, "%.2f", level))
                .add("field2", String.format(Locale.US, "%.2f", distance))
                .add("field3", String.valueOf(statusCode))
                .add("field4", "1")
                .add("field5", String.valueOf(epochSec))
                .build();

        Request request = new Request.Builder()
                .url(UPDATE_URL)
                .post(body)
                .build();

        try (Response response = client.newCall(request).execute()) {
            ResponseBody responseBody = response.body();
            String responseStr = (responseBody != null) ? responseBody.string().trim() : "";

            if (!response.isSuccessful()) {
                return new Result(false, false, "HTTP " + response.code(), 0);
            }

            if ("0".equals(responseStr) || responseStr.isEmpty()) {
                return new Result(false, true, "Rate limited by ThingSpeak (15s minimum)", 0);
            }

            long entryId = 1L;
            try {
                entryId = Long.parseLong(responseStr);
            } catch (NumberFormatException ignored) {}

            return new Result(true, false, "Success", entryId);
        } catch (IOException e) {
            return new Result(false, false, "Network I/O error: " + e.getMessage(), 0);
        }
    }
}
