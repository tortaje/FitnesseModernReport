package com.tortaje.fitnesse.plugins;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class MetricsParserTest {

    @Test
    void actuatorValue_readsTheMeasurementValue() {
        String json = "{\"name\":\"jvm.memory.used\",\"baseUnit\":\"bytes\",\"measurements\":[{\"statistic\":\"VALUE\",\"value\":1.2345678E8}],"
                + "\"availableTags\":[{\"tag\":\"area\",\"values\":[\"heap\",\"nonheap\"]}]}";
        assertEquals(123456780.0, MetricsParser.actuatorValue(json));
    }

    @Test
    void actuatorValue_withoutMeasurementThrows() {
        assertThrows(IllegalArgumentException.class, () -> MetricsParser.actuatorValue("{\"name\":\"x\",\"measurements\":[]}"));
    }

    private static final String PROMETHEUS = """
            # HELP jvm_memory_used_bytes The amount of used memory
            # TYPE jvm_memory_used_bytes gauge
            jvm_memory_used_bytes{area="heap",id="G1 Eden Space"} 1.0E8
            jvm_memory_used_bytes{area="heap",id="G1 Old Gen"} 5.0E7
            jvm_memory_used_bytes{area="nonheap",id="Metaspace"} 9.0E7
            jvm_memory_max_bytes{area="heap",id="G1 Eden Space"} -1.0
            jvm_memory_max_bytes{area="heap",id="G1 Old Gen"} 4.0E9
            process_cpu_usage 0.25
            system_cpu_usage 0.5
            """;

    @Test
    void prometheusSum_addsMatchingSeriesAndIgnoresUnknownMax() {
        assertEquals(1.5E8, MetricsParser.prometheusSum(PROMETHEUS, "jvm_memory_used_bytes", "area=\"heap\""));
        assertEquals(4.0E9, MetricsParser.prometheusSum(PROMETHEUS, "jvm_memory_max_bytes", "area=\"heap\""));
    }

    @Test
    void prometheusSum_readsUnlabelledMetric() {
        assertEquals(0.25, MetricsParser.prometheusSum(PROMETHEUS, "process_cpu_usage", null));
    }

    @Test
    void prometheusSum_missingMetricThrows() {
        assertThrows(IllegalArgumentException.class, () -> MetricsParser.prometheusSum(PROMETHEUS, "nope", null));
    }
}
