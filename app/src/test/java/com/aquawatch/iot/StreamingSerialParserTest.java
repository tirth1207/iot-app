package com.aquawatch.iot;

import com.aquawatch.iot.bluetooth.ParsedRecord;
import com.aquawatch.iot.bluetooth.StreamingSerialParser;
import com.google.common.truth.Truth;

import org.junit.Before;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

public class StreamingSerialParserTest {
    private StreamingSerialParser parser;

    @Before
    public void setUp() {
        parser = new StreamingSerialParser();
    }

    @Test
    public void testValidTelemetryLine() {
        byte[] payload = "72.4,14.8,NORMAL\r\n".getBytes(StandardCharsets.UTF_8);
        List<ParsedRecord> records = parser.feed(payload, payload.length);

        Truth.assertThat(records).hasSize(1);
        Truth.assertThat(records.get(0)).isInstanceOf(ParsedRecord.Telemetry.class);
        ParsedRecord.Telemetry t = (ParsedRecord.Telemetry) records.get(0);
        Truth.assertThat(t.levelPercent).isEqualTo(72.4f);
        Truth.assertThat(t.distanceCm).isEqualTo(14.8f);
        Truth.assertThat(t.status).isEqualTo("NORMAL");
    }

    @Test
    public void testFragmentedSerialStream() {
        byte[] part1 = "88.2,1".getBytes(StandardCharsets.UTF_8);
        byte[] part2 = "2.0,OVERFLOW\n".getBytes(StandardCharsets.UTF_8);

        List<ParsedRecord> res1 = parser.feed(part1, part1.length);
        Truth.assertThat(res1).isEmpty();

        List<ParsedRecord> res2 = parser.feed(part2, part2.length);
        Truth.assertThat(res2).hasSize(1);
        Truth.assertThat(res2.get(0)).isInstanceOf(ParsedRecord.Telemetry.class);
        ParsedRecord.Telemetry t = (ParsedRecord.Telemetry) res2.get(0);
        Truth.assertThat(t.levelPercent).isEqualTo(88.2f);
    }

    @Test
    public void testHardwareErrorNoEcho() {
        byte[] payload = "ERROR,NO_ECHO\r\n".getBytes(StandardCharsets.UTF_8);
        List<ParsedRecord> records = parser.feed(payload, payload.length);

        Truth.assertThat(records).hasSize(1);
        Truth.assertThat(records.get(0)).isInstanceOf(ParsedRecord.HardwareError.class);
        ParsedRecord.HardwareError err = (ParsedRecord.HardwareError) records.get(0);
        Truth.assertThat(err.errorCode).isEqualTo("NO_ECHO");
    }

    @Test
    public void testOutOfRangeLevelRejected() {
        byte[] payload = "150.0,10.0,NORMAL\n".getBytes(StandardCharsets.UTF_8);
        List<ParsedRecord> records = parser.feed(payload, payload.length);

        Truth.assertThat(records).hasSize(1);
        Truth.assertThat(records.get(0)).isInstanceOf(ParsedRecord.MalformedData.class);
    }
}
