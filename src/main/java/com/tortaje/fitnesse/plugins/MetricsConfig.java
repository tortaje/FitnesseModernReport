package com.tortaje.fitnesse.plugins;

import java.util.Locale;
import java.util.function.UnaryOperator;

/** Where and how often to poll the application under test, read from plugins.properties (report.metrics.*). */
public record MetricsConfig(String baseUrl, Format format, long intervalMs) {

    public enum Format { ACTUATOR, PROMETHEUS }

    static final String URL_KEY = "report.metrics.url";
    static final String FORMAT_KEY = "report.metrics.format";
    static final String INTERVAL_KEY = "report.metrics.interval";
    private static final long DEFAULT_INTERVAL_MS = 1000;
    private static final long MIN_INTERVAL_MS = 100;

    /** @return null when no url is configured (metrics disabled) */
    public static MetricsConfig from(UnaryOperator<String> property) {
        String url = property.apply(URL_KEY);
        if (url == null || url.isBlank()) {
            return null;
        }
        String baseUrl = url.trim().replaceAll("/+$", "");
        return new MetricsConfig(baseUrl, format(property.apply(FORMAT_KEY)), interval(property.apply(INTERVAL_KEY)));
    }

    private static Format format(String value) {
        if (value == null || value.isBlank()) {
            return Format.ACTUATOR;
        }
        try {
            return Format.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(FORMAT_KEY + " must be 'actuator' or 'prometheus', got '" + value + "'");
        }
    }

    private static long interval(String value) {
        if (value == null || value.isBlank()) {
            return DEFAULT_INTERVAL_MS;
        }
        try {
            long ms = Long.parseLong(value.trim());
            if (ms >= MIN_INTERVAL_MS) {
                return ms;
            }
        } catch (NumberFormatException ignored) {
            // reported below
        }
        throw new IllegalArgumentException(INTERVAL_KEY + " must be a number of milliseconds >= " + MIN_INTERVAL_MS + ", got '" + value + "'");
    }
}
