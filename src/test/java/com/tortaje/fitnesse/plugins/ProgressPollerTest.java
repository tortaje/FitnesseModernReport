package com.tortaje.fitnesse.plugins;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProgressPollerTest {

    @Test
    void poller_reportsTheInitialStateAndEveryChange() throws Exception {
        AtomicInteger completed = new AtomicInteger();
        List<String> seen = new CopyOnWriteArrayList<>();
        ProgressPoller poller = new ProgressPoller(completed::get, 3, 20, 60_000, (done, total) -> seen.add(done + "/" + total));
        poller.start();
        Thread.sleep(80);
        completed.set(1);
        Thread.sleep(80);
        completed.set(3);
        Thread.sleep(80);
        poller.stop();
        assertEquals(List.of("0/3", "1/3", "3/3"), seen);
    }

    @Test
    void poller_repeatsTheLastStateAsAHeartbeatWhenNothingChanges() throws Exception {
        List<String> seen = new CopyOnWriteArrayList<>();
        ProgressPoller poller = new ProgressPoller(() -> 2, 5, 20, 100, (done, total) -> seen.add(done + "/" + total));
        poller.start();
        Thread.sleep(450);
        poller.stop();
        assertTrue(seen.size() >= 3, "heartbeats: " + seen);
        assertTrue(seen.stream().allMatch("2/5"::equals), seen.toString());
    }

    @Test
    void poller_stopsReportingOnceTheListenerFails() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        AtomicInteger completed = new AtomicInteger();
        ProgressPoller poller = new ProgressPoller(completed::get, 9, 20, 60_000, (done, total) -> {
            calls.incrementAndGet();
            throw new IOException("Broken pipe");
        });
        poller.start();
        Thread.sleep(60);
        completed.set(4);
        Thread.sleep(100);
        poller.stop();
        assertEquals(1, calls.get(), "no further attempts after the client went away");
    }

    @Test
    void poller_stopIsIdempotentAndSafeBeforeStart() {
        ProgressPoller poller = new ProgressPoller(() -> 0, 1, 20, 1000, (d, t) -> { });
        poller.stop();
        poller.start();
        poller.stop();
        poller.stop();
    }
}
