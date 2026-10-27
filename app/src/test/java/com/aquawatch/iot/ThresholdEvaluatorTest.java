package com.aquawatch.iot;

import com.aquawatch.iot.domain.ThresholdEvaluator;
import com.google.common.truth.Truth;

import org.junit.Before;
import org.junit.Test;

public class ThresholdEvaluatorTest {
    private ThresholdEvaluator evaluator;

    @Before
    public void setUp() {
        evaluator = new ThresholdEvaluator();
    }

    @Test
    public void testTransitionToLowWaterTriggersAlertOnce() {
        ThresholdEvaluator.Result r1 = evaluator.evaluate(50.0f, 25.0f, 10.0f, 95.0f, false);
        Truth.assertThat(r1.status).isEqualTo(ThresholdEvaluator.Status.NORMAL);
        Truth.assertThat(r1.triggerAlert).isFalse();

        ThresholdEvaluator.Result r2 = evaluator.evaluate(8.0f, 45.0f, 10.0f, 95.0f, false);
        Truth.assertThat(r2.status).isEqualTo(ThresholdEvaluator.Status.EMPTY);
        Truth.assertThat(r2.triggerAlert).isTrue();

        ThresholdEvaluator.Result r3 = evaluator.evaluate(7.8f, 45.2f, 10.0f, 95.0f, false);
        Truth.assertThat(r3.status).isEqualTo(ThresholdEvaluator.Status.EMPTY);
        Truth.assertThat(r3.triggerAlert).isFalse();
    }

    @Test
    public void testHardwareFailureProducesSensorErrorState() {
        ThresholdEvaluator.Result r = evaluator.evaluate(0f, 0f, 10.0f, 95.0f, true);
        Truth.assertThat(r.status).isEqualTo(ThresholdEvaluator.Status.SENSOR_ERROR);
        Truth.assertThat(r.triggerAlert).isTrue();
    }
}
