package com.tortaje.fitnesse.plugins;

import java.io.IOException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.IntSupplier;

/** Reports progress while a suite runs: every change, plus a heartbeat so idle connections are not dropped. */
final class ProgressPoller {

    interface Listener {
        void onProgress(int done, int total) throws IOException;
    }

    private final IntSupplier completed;
    private final int total;
    private final long intervalMs;
    private final long heartbeatMs;
    private final Listener listener;
    private ScheduledExecutorService scheduler;
    private int lastDone = -1;
    private long lastReport;
    private boolean failed;

    ProgressPoller(IntSupplier completed, int total, long intervalMs, long heartbeatMs, Listener listener) {
        this.completed = completed;
        this.total = total;
        this.intervalMs = intervalMs;
        this.heartbeatMs = heartbeatMs;
        this.listener = listener;
    }

    void start() {
        scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "execution-report-progress");
            thread.setDaemon(true);
            return thread;
        });
        scheduler.scheduleWithFixedDelay(this::poll, 0, intervalMs, TimeUnit.MILLISECONDS);
    }

    /** Waits for a report in flight to finish, so nothing is written after this returns. */
    void stop() {
        if (scheduler == null) {
            return;
        }
        scheduler.shutdownNow();
        try {
            scheduler.awaitTermination(2, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void poll() {
        if (failed) {
            return;
        }
        try {
            int done = Math.min(completed.getAsInt(), total > 0 ? total : Integer.MAX_VALUE);
            long now = System.currentTimeMillis();
            if (done != lastDone || now - lastReport >= heartbeatMs) {
                lastDone = done;
                lastReport = now;
                listener.onProgress(done, total);
            }
        } catch (IOException e) {
            failed = true; // the browser went away: keep quiet, the suite itself carries on
        } catch (RuntimeException e) {
            // progress is cosmetic: a failing source must never disturb the run
        }
    }
}
