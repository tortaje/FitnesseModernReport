package com.tortaje.fitnesse.plugins;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/** Polls the application under test in the background while a suite runs. Never throws: failures end up in the result. */
public class MetricsSampler {

    public record Sample(long t, double heapUsed, double heapMax, double cpu) {}

    public record Result(List<Sample> samples, String error, long intervalMs) {

        public String toJson() {
            StringBuilder sb = new StringBuilder("{\"intervalMs\":").append(intervalMs)
                    .append(",\"error\":").append(error == null ? "null" : ResultParser.quote(error)).append(",\"samples\":[");
            for (int i = 0; i < samples.size(); i++) {
                Sample s = samples.get(i);
                if (i > 0) sb.append(',');
                sb.append("{\"t\":").append(s.t()).append(",\"heapUsed\":").append(s.heapUsed())
                        .append(",\"heapMax\":").append(s.heapMax()).append(",\"cpu\":").append(s.cpu()).append('}');
            }
            return sb.append("]}").toString();
        }
    }

    private final MetricsConfig config;
    private final ResultFetcher fetcher;
    private final List<Sample> samples = new CopyOnWriteArrayList<>();
    private volatile String firstError;
    private ScheduledExecutorService scheduler;
    private long startNanos;

    public MetricsSampler(MetricsConfig config, ResultFetcher fetcher) {
        this.config = config;
        this.fetcher = fetcher;
    }

    public void start() {
        startNanos = System.nanoTime();
        scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "execution-report-metrics");
            thread.setDaemon(true);
            return thread;
        });
        scheduler.scheduleWithFixedDelay(this::poll, 0, config.intervalMs(), TimeUnit.MILLISECONDS);
    }

    public Result stop() {
        if (scheduler != null) {
            scheduler.shutdownNow();
            try {
                scheduler.awaitTermination(2, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            poll();
        }
        return new Result(List.copyOf(samples), samples.isEmpty() ? firstError : null, config.intervalMs());
    }

    private void poll() {
        try {
            samples.add(MetricsParser.sample(config, fetcher, (System.nanoTime() - startNanos) / 1_000_000));
        } catch (RuntimeException e) {
            if (firstError == null) {
                firstError = e.getMessage();
            }
        }
    }
}
