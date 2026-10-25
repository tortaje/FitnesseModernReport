package com.tortaje.fitnesse.plugins;

import com.sun.net.httpserver.HttpServer;
import fitnesse.http.MockRequest;
import fitnesse.http.SimpleResponse;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExecutionReportResponderTest {

    private ExecutionReportResponder responder(String baseUrl) {
        return new ExecutionReportResponder(new ResultFetcher(), baseUrl);
    }

    @Test
    void makeResponse_withoutPage_servesEmptyDashboard() throws Exception {
        SimpleResponse response = (SimpleResponse) responder("http://127.0.0.1:1/").makeResponse(null, new MockRequest());
        assertEquals(200, response.getStatus());
        assertEquals("text/html; charset=utf-8", response.getContentType());
        assertTrue(response.getContent().contains("FitNesse Execution Report"));
        assertTrue(response.getContent().contains("startReport([],null)"), "no suite: empty report and no metrics");
    }

    @Test
    void responder_hasNoArgConstructorForFitNesseInstantiation() {
        new ExecutionReportResponder();
    }

    @Test
    void makeResponse_jsonFormat_returnsScenariosRelativeToSuite() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            byte[] body = ("<testResults><rootPath>Rerun_S</rootPath><result><counts><right>1</right><wrong>0</wrong><ignores>0</ignores><exceptions>0</exceptions></counts>"
                    + "<runTimeInMillis>7</runTimeInMillis><relativePageName>Leaf</relativePageName><pageHistoryLink>S.Sub.Leaf?pageHistory</pageHistoryLink></result></testResults>").getBytes();
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            MockRequest request = new MockRequest();
            request.addInput("page", "Rerun_S");
            request.addInput("relativeTo", "S");
            request.addInput("format", "json");
            SimpleResponse response = (SimpleResponse) responder("http://127.0.0.1:" + server.getAddress().getPort() + "/").makeResponse(null, request);
            assertEquals(200, response.getStatus());
            assertEquals("application/json; charset=utf-8", response.getContentType());
            assertTrue(response.getContent().startsWith("[{\"name\":\"Sub.Leaf\""), response.getContent());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void makeResponse_jsonFormat_whenFetchFails_returns502Json() throws Exception {
        MockRequest request = new MockRequest();
        request.addInput("page", "S");
        request.addInput("format", "json");
        SimpleResponse response = (SimpleResponse) responder("http://127.0.0.1:1/").makeResponse(null, request);
        assertEquals(502, response.getStatus());
        assertEquals("application/json; charset=utf-8", response.getContentType());
        assertTrue(response.getContent().startsWith("{\"error\":"), response.getContent());
    }

    @Test
    void responder_isSecuredByFitNesseAuthentication() {
        ExecutionReportResponder responder = new ExecutionReportResponder();
        assertTrue(responder instanceof fitnesse.authentication.SecureResponder);
        assertTrue(responder.getSecureOperation() instanceof fitnesse.authentication.AlwaysSecureOperation);
    }

    @Test
    void makeResponse_isSelfContained_withoutAnyExternalScript() throws Exception {
        SimpleResponse response = (SimpleResponse) responder("http://127.0.0.1:1/").makeResponse(null, new MockRequest());
        String content = response.getContent();
        assertFalse(content.matches("(?s).*<script[^>]*\\ssrc=.*"), "no <script src=...>");
        assertFalse(content.matches("(?s).*<link[^>]*href=\"https?:.*"), "no external stylesheet");
        assertTrue(content.contains("JSZip v3.10.1"), "JSZip is embedded");
        assertTrue(content.contains("var pr=\"4.3.3\""), "Tailwind browser build is embedded");
        assertFalse(content.contains("/*__TAILWIND__*/") || content.contains("/*__JSZIP__*/"), "placeholders are replaced");
    }

    @Test
    void embeddedLibraries_cannotBreakOutOfTheirScriptTag() throws Exception {
        for (String library : List.of("/vendor/tailwindcss-browser-4.3.3.js", "/vendor/jszip-3.10.1.min.js")) {
            try (java.io.InputStream in = getClass().getResourceAsStream(library)) {
                String code = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8).toLowerCase();
                assertFalse(code.contains("</script"), library + " is inlined in a <script> element");
                assertFalse(code.contains("<!--"), library);
            }
        }
    }

    @Test
    void dashboardTemplate_hasNoHtmlCommentOpenerThatCouldSwallowThePage() throws Exception {
        // "<!--" followed by "<script" inside a script element puts the HTML parser in the "double escaped" state
        // and the rest of the document disappears (found in the browser, not by the other tests)
        try (java.io.InputStream in = getClass().getResourceAsStream("/dashboard.html")) {
            assertFalse(new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8).contains("<!--"));
        }
    }

    @Test
    void dashboardTemplate_retryButtonIsHiddenUntilAnErrorIsShown() throws Exception {
        // "hidden" and "inline-flex" both set display: with both classes the button showed during the progress
        try (java.io.InputStream in = getClass().getResourceAsStream("/dashboard.html")) {
            String html = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            int at = html.indexOf("id=\"progressRetry\"");
            String tag = html.substring(html.lastIndexOf('<', at), html.indexOf('>', at));
            assertTrue(tag.contains("hidden"), tag);
            assertFalse(tag.contains("flex"), "no display utility may compete with 'hidden': " + tag);
        }
    }
}
