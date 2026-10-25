package com.tortaje.fitnesse.plugins;

import fitnesse.ContextConfigurator;
import fitnesse.FitNesse;
import fitnesse.FitNesseContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExecutionReportPluginIntegrationTest {

    @Test
    void executionReportEndpoint_returns200WithDashboard(@TempDir Path root) throws Exception {
        try (ServerSocket socket = new ServerSocket(0)) {
            FitNesseContext context = ContextConfigurator.systemDefaults()
                    .withRootPath(root.toString())
                    .withPort(socket.getLocalPort())
                    .withClassLoader(getClass().getClassLoader())
                    .withParameter("Plugins", ExecutionReportPlugin.class.getName())
                    .makeFitNesseContext();
            FitNesse fitnesse = new FitNesse(context);
            fitnesse.start(socket);
            try {
                HttpResponse<String> response = HttpClient.newHttpClient().send(
                        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + socket.getLocalPort()
                                + "/?responder=ExecutionReport")).GET().build(),
                        HttpResponse.BodyHandlers.ofString());
                assertEquals(200, response.statusCode(), response.body());
                assertTrue(response.body().contains("FitNesse Execution Report"));
            } finally {
                fitnesse.stop();
            }
        }
    }
}
