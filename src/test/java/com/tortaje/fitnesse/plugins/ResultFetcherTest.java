package com.tortaje.fitnesse.plugins;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.URI;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ResultFetcherTest {

    @Test
    void fetchResults_returnsResponseBody() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            byte[] body = "<testResults/>".getBytes();
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            URI uri = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/");
            assertEquals("<testResults/>", new ResultFetcher().fetchResults(uri));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void fetchResults_wrapsConnectionFailure() {
        ResultFetcher fetcher = new ResultFetcher();
        assertThrows(RuntimeException.class, () -> fetcher.fetchResults(URI.create("http://127.0.0.1:1/")));
    }

    @Test
    void fetchResults_failureMessageIncludesCause() {
        ResultFetcher fetcher = new ResultFetcher();
        RuntimeException e = assertThrows(RuntimeException.class, () -> fetcher.fetchResults(URI.create("http://127.0.0.1:1/")));
        assertTrue(e.getMessage().contains(e.getCause().getClass().getSimpleName()), e.getMessage());
    }

    @Test
    void fetchResults_readsChunkedResponseWithTrailer() throws Exception {
        java.net.ServerSocket server = new java.net.ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress());
        Thread thread = new Thread(() -> {
            try (java.net.Socket socket = server.accept()) {
                socket.getInputStream().read(new byte[4096]);
                socket.getOutputStream().write(("HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\nConnection: close\r\n\r\n"
                        + "5\r\n<a/>\n\r\n4\r\n<b/>\r\n0\r\nExit-Code: 1\r\n\r\n").getBytes());
            } catch (Exception ignored) {
            }
        });
        thread.start();
        try {
            URI uri = URI.create("http://127.0.0.1:" + server.getLocalPort() + "/Suite?suite&format=xml");
            assertEquals("<a/>\n<b/>", new ResultFetcher().fetchResults(uri));
        } finally {
            server.close();
        }
    }

    @Test
    void fetchResults_failsOnHttpErrorStatus() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            exchange.sendResponseHeaders(404, -1);
            exchange.close();
        });
        server.start();
        try {
            URI uri = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/");
            RuntimeException e = assertThrows(RuntimeException.class, () -> new ResultFetcher().fetchResults(uri));
            assertTrue(e.getMessage().contains("404"), e.getMessage());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void fetchResults_sendsExtraHeadersAndStripsLineBreaks() throws Exception {
        java.util.concurrent.atomic.AtomicReference<String> seen = new java.util.concurrent.atomic.AtomicReference<>();
        java.util.concurrent.atomic.AtomicReference<String> injected = new java.util.concurrent.atomic.AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            seen.set(exchange.getRequestHeaders().getFirst("Authorization"));
            injected.set(exchange.getRequestHeaders().getFirst("X-Injected"));
            exchange.sendResponseHeaders(200, 2);
            exchange.getResponseBody().write("ok".getBytes());
            exchange.close();
        });
        server.start();
        try {
            URI uri = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/");
            new ResultFetcher().fetchResults(uri, java.util.Map.of("Authorization", "Basic abc\r\nX-Injected: yes"));
            assertEquals("Basic abcX-Injected: yes", seen.get());
            assertEquals(null, injected.get());
        } finally {
            server.stop(0);
        }
    }
}
