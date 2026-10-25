package com.tortaje.fitnesse.plugins;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class MetricsConfigTest {

    private static MetricsConfig from(Map<String, String> props) {
        return MetricsConfig.from(props::get);
    }

    @Test
    void from_withoutUrl_isDisabled() {
        assertNull(from(Map.of()));
        assertNull(from(Map.of("report.metrics.url", "  ")));
    }

    @Test
    void from_appliesDefaults() {
        MetricsConfig config = from(Map.of("report.metrics.url", "http://app:8080/actuator/metrics/"));
        assertEquals("http://app:8080/actuator/metrics", config.baseUrl());
        assertEquals(MetricsConfig.Format.ACTUATOR, config.format());
        assertEquals(1000, config.intervalMs());
    }

    @Test
    void from_readsFormatAndInterval() {
        MetricsConfig config = from(Map.of("report.metrics.url", "http://app/q/metrics",
                "report.metrics.format", "Prometheus", "report.metrics.interval", "250"));
        assertEquals(MetricsConfig.Format.PROMETHEUS, config.format());
        assertEquals(250, config.intervalMs());
    }

    @Test
    void from_rejectsUnknownFormatAndBadInterval() {
        assertThrows(IllegalArgumentException.class, () -> from(Map.of("report.metrics.url", "http://a", "report.metrics.format", "json")));
        assertThrows(IllegalArgumentException.class, () -> from(Map.of("report.metrics.url", "http://a", "report.metrics.interval", "fast")));
        assertThrows(IllegalArgumentException.class, () -> from(Map.of("report.metrics.url", "http://a", "report.metrics.interval", "10")));
    }
}
