package com.tortaje.fitnesse.plugins;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class MetricsSamplerTest {

    private static HttpServer actuator(AtomicInteger counter) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/actuator/metrics/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            double value = path.endsWith("jvm.memory.used") ? 1.0E8 + counter.incrementAndGet() * 1.0E6
                    : path.endsWith("jvm.memory.max") ? 4.0E9 : 0.4;
            byte[] body = ("{\"name\":\"m\",\"measurements\":[{\"statistic\":\"VALUE\",\"value\":" + value + "}]}").getBytes();
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        return server;
    }

    @Test
    void sampler_collectsSamplesWhileRunning() throws Exception {
        HttpServer server = actuator(new AtomicInteger());
        try {
            MetricsConfig config = new MetricsConfig("http://127.0.0.1:" + server.getAddress().getPort() + "/actuator/metrics", MetricsConfig.Format.ACTUATOR, 100);
            MetricsSampler sampler = new MetricsSampler(config, new ResultFetcher());
            sampler.start();
            Thread.sleep(450);
            MetricsSampler.Result result = sampler.stop();
            assertNull(result.error());
            assertTrue(result.samples().size() >= 4, "samples: " + result.samples().size());
            MetricsSampler.Sample first = result.samples().get(0), last = result.samples().get(result.samples().size() - 1);
            assertEquals(0, first.t(), 50);
            assertTrue(last.t() > first.t());
            assertTrue(last.heapUsed() > first.heapUsed());
            assertEquals(4.0E9, first.heapMax());
            assertEquals(0.4, first.cpu());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void sampler_whenTargetIsDown_reportsErrorWithoutThrowing() {
        MetricsConfig config = new MetricsConfig("http://127.0.0.1:1/actuator/metrics", MetricsConfig.Format.ACTUATOR, 100);
        MetricsSampler sampler = new MetricsSampler(config, new ResultFetcher());
        sampler.start();
        MetricsSampler.Result result = sampler.stop();
        assertTrue(result.samples().isEmpty());
        assertNotNull(result.error());
        assertTrue(result.toJson().contains("\"error\":\""), result.toJson());
    }

    @Test
    void result_toJsonDescribesSamples() {
        MetricsSampler.Result result = new MetricsSampler.Result(
                java.util.List.of(new MetricsSampler.Sample(0, 1.0E8, 4.0E9, 0.25)), null, 1000);
        assertEquals("{\"intervalMs\":1000,\"error\":null,\"samples\":[{\"t\":0,\"heapUsed\":1.0E8,\"heapMax\":4.0E9,\"cpu\":0.25}]}", result.toJson());
    }
}
