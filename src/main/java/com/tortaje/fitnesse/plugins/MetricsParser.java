package com.tortaje.fitnesse.plugins;

import java.net.URI;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Reads heap and CPU figures from Spring Boot Actuator or Micrometer/Prometheus (Quarkus) endpoints. */
final class MetricsParser {

    private static final Pattern ACTUATOR_VALUE = Pattern.compile("\"value\"\\s*:\\s*(-?[0-9.]+(?:[eE][-+]?[0-9]+)?)");

    private MetricsParser() {}

    /** One poll of the target application; heapMax and cpu are -1 when the target does not expose them. */
    static MetricsSampler.Sample sample(MetricsConfig config, ResultFetcher fetcher, long t) {
        return config.format() == MetricsConfig.Format.ACTUATOR ? fromActuator(config, fetcher, t) : fromPrometheus(config, fetcher, t);
    }

    private static MetricsSampler.Sample fromActuator(MetricsConfig config, ResultFetcher fetcher, long t) {
        double used = actuatorValue(fetcher.fetchResults(URI.create(config.baseUrl() + "/jvm.memory.used?tag=area:heap")));
        double max = optional(() -> actuatorValue(fetcher.fetchResults(URI.create(config.baseUrl() + "/jvm.memory.max?tag=area:heap"))));
        double cpu = optional(() -> actuatorValue(fetcher.fetchResults(URI.create(config.baseUrl() + "/process.cpu.usage"))));
        return new MetricsSampler.Sample(t, used, max > 0 ? max : -1, cpu);
    }

    private static MetricsSampler.Sample fromPrometheus(MetricsConfig config, ResultFetcher fetcher, long t) {
        String text = fetcher.fetchResults(URI.create(config.baseUrl()));
        double used = prometheusSum(text, "jvm_memory_used_bytes", "area=\"heap\"");
        double max = optional(() -> prometheusSum(text, "jvm_memory_max_bytes", "area=\"heap\""));
        double cpu = optional(() -> prometheusSum(text, "process_cpu_usage", null));
        return new MetricsSampler.Sample(t, used, max, cpu);
    }

    static double actuatorValue(String json) {
        Matcher matcher = ACTUATOR_VALUE.matcher(json);
        if (!matcher.find()) {
            throw new IllegalArgumentException("No measurement value in metrics response");
        }
        return Double.parseDouble(matcher.group(1));
    }

    /** Sums the samples of a metric whose label block contains {@code labelFilter}; negative values (unknown max) are ignored. */
    static double prometheusSum(String text, String metric, String labelFilter) {
        double sum = 0;
        boolean found = false;
        for (String line : text.split("\n")) {
            if (!line.startsWith(metric) || line.length() == metric.length()) {
                continue;
            }
            char next = line.charAt(metric.length());
            if (next != '{' && next != ' ') {
                continue;
            }
            if (labelFilter != null && !line.contains(labelFilter)) {
                continue;
            }
            String[] parts = line.trim().split("\\s+");
            double value = Double.parseDouble(parts[parts.length - 1]);
            if (value >= 0) {
                sum += value;
                found = true;
            }
        }
        if (!found) {
            throw new IllegalArgumentException("Metric not found: " + metric);
        }
        return sum;
    }

    private static double optional(java.util.function.DoubleSupplier supplier) {
        try {
            return supplier.getAsDouble();
        } catch (RuntimeException e) {
            return -1;
        }
    }
}
