package com.tortaje.fitnesse.plugins;

import fitnesse.FitNesseContext;
import fitnesse.authentication.AlwaysSecureOperation;
import fitnesse.authentication.SecureOperation;
import fitnesse.authentication.SecureResponder;
import fitnesse.http.ChunkedResponse;
import fitnesse.http.Request;
import fitnesse.http.Response;
import fitnesse.http.SimpleResponse;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.IntSupplier;

/**
 * Secured through FitNesse's authenticator: the report starts a suite run, so it must not be reachable by callers
 * FitNesse itself would refuse. The caller's credentials are forwarded on the call-backs.
 *
 * <p>With a {@code page} the response is streamed: the page and its progress bar are sent at once, progress follows
 * while FitNesse runs the suite, and the report itself closes the page. Errors that happen after the response has
 * started travel inside the page, since the HTTP status has already been sent.
 */
public class ExecutionReportResponder implements SecureResponder {

    /** Tells how many of the suite's tests have completed; returns null when progress cannot be followed. */
    interface ProgressSource {
        IntSupplier start(FitNesseContext context, List<String> pages, long startMillis);
    }

    private static final String CONTENT_TYPE = "text/html; charset=utf-8";
    private static final String JSON_CONTENT_TYPE = "application/json; charset=utf-8";
    private static final String TAILWIND_PLACEHOLDER = "/*__TAILWIND__*/";
    private static final String JSZIP_PLACEHOLDER = "/*__JSZIP__*/";
    private static final long DEFAULT_POLL_INTERVAL_MS = 500;
    private static final long HEARTBEAT_MS = 10_000;

    private final ResultFetcher resultFetcher;
    private final String baseUrl;
    private final Function<FitNesseContext, MetricsConfig> metricsConfig;
    private final ProgressSource progressSource;
    private final long pollIntervalMs;

    /** Instantiated by FitNesse: metrics are configured through plugins.properties, progress comes from the test history. */
    public ExecutionReportResponder() {
        this.resultFetcher = new ResultFetcher();
        this.baseUrl = null;
        this.metricsConfig = ExecutionReportResponder::metricsFromContext;
        this.progressSource = ExecutionReportResponder::progressFromHistory;
        this.pollIntervalMs = DEFAULT_POLL_INTERVAL_MS;
    }

    public ExecutionReportResponder(ResultFetcher resultFetcher, String baseUrl) {
        this(resultFetcher, baseUrl, null);
    }

    public ExecutionReportResponder(ResultFetcher resultFetcher, String baseUrl, MetricsConfig metrics) {
        this(resultFetcher, baseUrl, metrics, (context, pages, start) -> null, DEFAULT_POLL_INTERVAL_MS);
    }

    ExecutionReportResponder(ResultFetcher resultFetcher, String baseUrl, MetricsConfig metrics, ProgressSource progressSource, long pollIntervalMs) {
        this.resultFetcher = resultFetcher;
        this.baseUrl = baseUrl;
        this.metricsConfig = context -> metrics;
        this.progressSource = progressSource;
        this.pollIntervalMs = pollIntervalMs;
    }

    private static MetricsConfig metricsFromContext(FitNesseContext context) {
        return MetricsConfig.from(context.variableSource::getProperty);
    }

    private static IntSupplier progressFromHistory(FitNesseContext context, List<String> pages, long startMillis) {
        return context == null ? null : RunProgress.fromHistory(context.getTestHistoryDirectory().toPath(), pages, startMillis);
    }

    @Override
    public SecureOperation getSecureOperation() {
        return new AlwaysSecureOperation();
    }

    @Override
    public Response makeResponse(FitNesseContext context, Request request) throws Exception {
        String page = request.getInput("page");
        if ("json".equals(request.getInput("format"))) {
            return jsonResponse(context, request, page);
        }
        if (page == null || page.isBlank()) {
            String[] template = splitTemplate();
            SimpleResponse response = new SimpleResponse();
            response.setContentType(CONTENT_TYPE);
            response.setContent(template[0] + "<script>startReport([],null)</script>\n" + template[1]);
            return response;
        }
        ChunkedResponse[] response = new ChunkedResponse[1];
        response[0] = new ChunkedResponse("html", () -> stream(response[0], context, request, page));
        return response[0];
    }

    /** Runs while the response is open: everything the browser sees after the first byte is produced here. */
    private void stream(ChunkedResponse response, FitNesseContext context, Request request, String page) throws IOException {
        Object lock = new Object();
        MetricsSampler sampler = null;
        ProgressPoller poller = null;
        try {
            Map<String, String> credentials = credentials(request);
            String[] template = splitTemplate();
            write(response, lock, template[0]);

            long startedAt = System.currentTimeMillis();
            sampler = startSampler(context);
            List<String> pages = pagesToRun(context, page, credentials);
            poller = startPoller(context, pages, startedAt, response, lock);

            String closing;
            try {
                String xml = resultFetcher.fetchResults(endpoint(context, page, "suite&format=xml&includehtml"), credentials);
                String json = ResultParser.toJson(ResultParser.parse(xml));
                poller.stop();
                String metricsJson = sampler == null ? "null" : sampler.stop().toJson();
                sampler = null;
                closing = "<script>startReport(" + json + "," + metricsJson + ")</script>\n";
            } catch (RuntimeException e) {
                String retry = "?responder=ExecutionReport&page=" + URLEncoder.encode(page, StandardCharsets.UTF_8);
                closing = "<script>showError(" + ResultParser.quote(String.valueOf(e.getMessage())) + "," + ResultParser.quote(retry) + ")</script>\n";
            }
            if (poller != null) {
                poller.stop();
            }
            write(response, lock, closing + template[1]);
        } catch (IOException clientLeft) {
            // the browser went away: the suite has run anyway, there is nobody left to show the report to
        } finally {
            if (poller != null) {
                poller.stop();
            }
            if (sampler != null) {
                sampler.stop();
            }
            try {
                response.close();
            } catch (IOException ignored) {
                // nothing more can be sent
            }
        }
    }

    private static void write(ChunkedResponse response, Object lock, String text) throws IOException {
        synchronized (lock) {
            response.add(text);
        }
    }

    /** The suite's tests, listed by FitNesse without running them; empty when that is not possible. */
    private List<String> pagesToRun(FitNesseContext context, String page, Map<String, String> credentials) {
        try {
            return RunProgress.pagesFromPartition(resultFetcher.fetchResults(endpoint(context, page, "partition&format=tsv"), credentials));
        } catch (RuntimeException e) {
            return List.of();
        }
    }

    private ProgressPoller startPoller(FitNesseContext context, List<String> pages, long startedAt, ChunkedResponse response, Object lock) {
        IntSupplier completed = pages.isEmpty() ? null : progressSource.start(context, pages, startedAt);
        ProgressPoller poller = new ProgressPoller(completed != null ? completed : () -> 0, pages.size(), pollIntervalMs, HEARTBEAT_MS,
                (done, total) -> write(response, lock, "<script>updateProgress(" + done + "," + total + ")</script>\n"));
        poller.start();
        return poller;
    }

    /** Polling runs for as long as the suite executes, since the suite runs inside the results request. */
    private MetricsSampler startSampler(FitNesseContext context) {
        try {
            MetricsConfig config = metricsConfig.apply(context);
            if (config == null) {
                return null;
            }
            MetricsSampler sampler = new MetricsSampler(config, resultFetcher);
            sampler.start();
            return sampler;
        } catch (RuntimeException e) {
            return null;
        }
    }

    private Response jsonResponse(FitNesseContext context, Request request, String page) throws IOException {
        SimpleResponse response = new SimpleResponse();
        response.setContentType(JSON_CONTENT_TYPE);
        if (page == null || page.isBlank()) {
            response.setStatus(400);
            response.setContent("{\"error\":\"Missing 'page' parameter\"}");
            return response;
        }
        try {
            String xml = resultFetcher.fetchResults(endpoint(context, page, "suite&format=xml&includehtml"), credentials(request));
            response.setContent(ResultParser.toJson(ResultParser.parse(xml, request.getInput("relativeTo"))));
        } catch (RuntimeException e) {
            response.setStatus(502);
            response.setContent("{\"error\":" + ResultParser.quote(String.valueOf(e.getMessage())) + "}");
        }
        return response;
    }

    /** The call-backs go through FitNesse's own pipeline, so they need the caller's credentials. */
    private static Map<String, String> credentials(Request request) {
        return request.hasHeader("Authorization") ? Map.of("Authorization", String.valueOf(request.getHeader("Authorization"))) : Map.of();
    }

    private URI endpoint(FitNesseContext context, String page, String query) {
        String base = baseUrl != null ? baseUrl : "http://127.0.0.1:" + context.port + context.contextRoot;
        return URI.create(base + URLEncoder.encode(page, StandardCharsets.UTF_8).replace("%2E", ".") + "?" + query);
    }

    /** The template cut where the report itself will be appended: everything before {@code </body>}, and the rest. */
    private String[] splitTemplate() throws IOException {
        String html = loadResource("/dashboard.html")
                .replace(TAILWIND_PLACEHOLDER, loadResource("/vendor/tailwindcss-browser-4.3.3.js"))
                .replace(JSZIP_PLACEHOLDER, loadResource("/vendor/jszip-3.10.1.min.js"));
        int end = html.lastIndexOf("</body>");
        return new String[] {html.substring(0, end), html.substring(end)};
    }

    /** Vendored libraries are served from the jar so the report works without internet access. */
    private String loadResource(String path) throws IOException {
        try (InputStream in = getClass().getResourceAsStream(path)) {
            if (in == null) {
                throw new IOException(path + " not found on classpath");
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
