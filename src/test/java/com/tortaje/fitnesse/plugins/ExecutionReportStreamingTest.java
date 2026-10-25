package com.tortaje.fitnesse.plugins;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import fitnesse.http.MockRequest;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The report page is streamed: it appears at once and shows progress while FitNesse is still running the suite. */
class ExecutionReportStreamingTest {

    private static final String PARTITION = "Page\tPartition\tTest System\tOrder\nSuite.GoodTest\t0\tslim\t0\nSuite.BadTest\t0\tslim\t1\nSuite.Other\t0\tslim\t2\n";

    private interface SuiteHandler {
        void handle(HttpExchange exchange) throws Exception;
    }

    /** Stands in for FitNesse: partition list, suite results and (optionally) the metrics of the application under test. */
    private static HttpServer fitnesse(SuiteHandler suite) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            try {
                String path = exchange.getRequestURI().getPath();
                String query = String.valueOf(exchange.getRequestURI().getRawQuery());
                if (path.startsWith("/actuator/metrics/")) {
                    double value = path.endsWith("jvm.memory.used") ? 2.0E8 : path.endsWith("jvm.memory.max") ? 4.0E9 : 0.3;
                    respond(exchange, 200, "{\"measurements\":[{\"statistic\":\"VALUE\",\"value\":" + value + "}]}");
                } else if (query.contains("partition")) {
                    respond(exchange, 200, PARTITION);
                } else {
                    suite.handle(exchange);
                }
            } catch (Exception e) {
                exchange.close();
            }
        });
        server.start();
        return server;
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes();
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private static String base(HttpServer server) {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private static MockRequest suiteRequest() {
        MockRequest request = new MockRequest();
        request.addInput("page", "Suite");
        return request;
    }

    private static ExecutionReportResponder responder(HttpServer server) {
        return new ExecutionReportResponder(new ResultFetcher(), base(server) + "/");
    }

    @Test
    void response_isChunkedHtmlAndSuccessful() throws Exception {
        HttpServer server = fitnesse(exchange -> respond(exchange, 200, ResultParserTest.XML));
        try {
            RenderedResponse page = RenderedResponse.of(responder(server).makeResponse(null, suiteRequest()));
            assertEquals(200, page.status);
            assertEquals("text/html; charset=utf-8", page.contentType.toLowerCase());
            assertTrue(page.body.startsWith("<!DOCTYPE html>"), page.body.substring(0, 40));
            assertTrue(page.body.trim().endsWith("</html>"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void page_isSentBeforeTheSuiteHasFinished() throws Exception {
        CountDownLatch pageReceived = new CountDownLatch(1);
        AtomicBoolean pageArrivedWhileSuiteWasRunning = new AtomicBoolean();
        HttpServer server = fitnesse(exchange -> {
            // FitNesse is "running the suite" until the browser has received the page
            pageArrivedWhileSuiteWasRunning.set(pageReceived.await(5, TimeUnit.SECONDS));
            respond(exchange, 200, ResultParserTest.XML);
        });
        try {
            RenderedResponse.of(responder(server).makeResponse(null, suiteRequest()), text -> {
                if (text.contains("function startReport")) {
                    pageReceived.countDown();
                }
            });
            assertTrue(pageArrivedWhileSuiteWasRunning.get(), "the page must reach the browser before the suite results do");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void progress_isPushedWithTheTotalFromTheSuiteAndThenTheReport() throws Exception {
        AtomicInteger polls = new AtomicInteger();
        HttpServer server = fitnesse(exchange -> {
            Thread.sleep(1000); // long enough for several polls, even on a slow machine
            respond(exchange, 200, ResultParserTest.XML);
        });
        try {
            // one more test "completes" at every poll: 0, 1, 2, then 3 of 3
            ExecutionReportResponder responder = new ExecutionReportResponder(new ResultFetcher(), base(server) + "/", null,
                    (context, pages, start) -> () -> Math.min(3, polls.getAndIncrement()), 40);
            RenderedResponse page = RenderedResponse.of(responder.makeResponse(null, suiteRequest()));
            String body = page.body;
            int first = body.indexOf("updateProgress(0,3)");
            int last = body.indexOf("updateProgress(3,3)");
            int report = body.indexOf("startReport([");
            assertTrue(first >= 0, "initial progress, with a total of 3 tests: " + tail(body));
            assertTrue(body.contains("updateProgress(1,3)"), "an intermediate step: " + tail(body));
            assertTrue(last > first, "final step after the first");
            assertTrue(report > last, "the report comes after the progress");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void report_carriesTheFetchedResults() throws Exception {
        HttpServer server = fitnesse(exchange -> respond(exchange, 200, ResultParserTest.XML));
        try {
            RenderedResponse page = RenderedResponse.of(responder(server).makeResponse(null, suiteRequest()));
            assertTrue(page.body.contains("startReport([{\"name\":\"Suite.GoodTest\""), tail(page.body));
            assertTrue(page.body.contains("Suite.BadTest"));
            assertFalse(page.body.contains("<script>showError("), "no error call");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void whenTheSuiteCannotBeFetched_thePageShowsTheErrorAndARetryLink() throws Exception {
        HttpServer server = fitnesse(exchange -> respond(exchange, 404, "not found"));
        try {
            RenderedResponse page = RenderedResponse.of(responder(server).makeResponse(null, suiteRequest()));
            assertEquals(200, page.status, "headers are sent before the suite runs, so the error travels inside the page");
            assertTrue(page.body.contains("<script>showError(\"Failed to fetch results"), tail(page.body));
            assertTrue(page.body.contains("HTTP 404"));
            assertTrue(page.body.contains("?responder=ExecutionReport\\u0026page=Suite"), "retry link: " + tail(page.body));
            assertFalse(page.body.contains("startReport([{"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void whenFitNesseIsUnreachable_thePageStillShowsTheError() throws Exception {
        RenderedResponse page = RenderedResponse.of(new ExecutionReportResponder(new ResultFetcher(), "http://127.0.0.1:1/").makeResponse(null, suiteRequest()));
        assertEquals(200, page.status);
        assertTrue(page.body.contains("<script>showError("));
    }

    @Test
    void progressStillWorksWithoutATotal_whenThePartitionListIsUnavailable() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            if (String.valueOf(exchange.getRequestURI().getRawQuery()).contains("partition")) {
                respond(exchange, 500, "boom");
            } else {
                try {
                    Thread.sleep(200);
                } catch (InterruptedException ignored) {
                }
                respond(exchange, 200, ResultParserTest.XML);
            }
        });
        server.start();
        try {
            ExecutionReportResponder responder = new ExecutionReportResponder(new ResultFetcher(), base(server) + "/", null,
                    (context, pages, start) -> () -> 0, 40);
            RenderedResponse page = RenderedResponse.of(responder.makeResponse(null, suiteRequest()));
            assertTrue(page.body.contains("updateProgress(0,0)"), tail(page.body));
            assertTrue(page.body.contains("startReport([{"), "the report is produced anyway");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void metrics_areSampledWhileTheSuiteRunsAndPassedToTheReport() throws Exception {
        HttpServer server = fitnesse(exchange -> {
            Thread.sleep(400);
            respond(exchange, 200, ResultParserTest.XML);
        });
        try {
            MetricsConfig metrics = new MetricsConfig(base(server) + "/actuator/metrics", MetricsConfig.Format.ACTUATOR, 100);
            RenderedResponse page = RenderedResponse.of(new ExecutionReportResponder(new ResultFetcher(), base(server) + "/", metrics).makeResponse(null, suiteRequest()));
            assertTrue(page.body.contains("{\"intervalMs\":100,\"error\":null,\"samples\":[{"), tail(page.body));
            assertTrue(page.body.contains("\"heapUsed\":2.0E8"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void metrics_whenTheTargetIsDown_theReportIsStillProduced() throws Exception {
        HttpServer server = fitnesse(exchange -> respond(exchange, 200, ResultParserTest.XML));
        try {
            MetricsConfig metrics = new MetricsConfig("http://127.0.0.1:1/actuator/metrics", MetricsConfig.Format.ACTUATOR, 100);
            RenderedResponse page = RenderedResponse.of(new ExecutionReportResponder(new ResultFetcher(), base(server) + "/", metrics).makeResponse(null, suiteRequest()));
            assertTrue(page.body.contains("Suite.BadTest"));
            assertTrue(page.body.contains("\"samples\":[]"));
            assertTrue(page.body.contains("\"error\":\""));
        } finally {
            server.stop(0);
        }
    }

    private String authorizationSeenByCallback(MockRequest request) throws Exception {
        AtomicReference<String> seen = new AtomicReference<>();
        AtomicReference<String> seenOnPartition = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            if (String.valueOf(exchange.getRequestURI().getRawQuery()).contains("partition")) {
                seenOnPartition.set(exchange.getRequestHeaders().getFirst("Authorization"));
                respond(exchange, 200, PARTITION);
            } else {
                seen.set(exchange.getRequestHeaders().getFirst("Authorization"));
                respond(exchange, 200, ResultParserTest.XML);
            }
        });
        server.start();
        try {
            RenderedResponse.of(responder(server).makeResponse(null, request));
            assertEquals(seen.get(), seenOnPartition.get(), "the partition call needs the same credentials");
            return seen.get();
        } finally {
            server.stop(0);
        }
    }

    @Test
    void credentials_areForwardedToEveryCallback() throws Exception {
        MockRequest request = suiteRequest();
        request.addHeader("Authorization", "Basic dXNlcjpwdw==");
        assertEquals("Basic dXNlcjpwdw==", authorizationSeenByCallback(request));
    }

    @Test
    void withoutCredentials_noAuthorizationHeaderIsSent() throws Exception {
        assertNull(authorizationSeenByCallback(suiteRequest()));
    }

    private static String tail(String body) {
        return body.substring(Math.max(0, body.length() - 600));
    }
}
