package com.tortaje.fitnesse.plugins;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;

/**
 * Minimal HTTP/1.1 client for FitNesse's own endpoints. FitNesse streams suite results chunked and
 * appends a trailer ("Exit-Code") that the JDK HttpClient can reject ("CR expected"), so the response is read by hand.
 */
public class ResultFetcher {

    private static final int CONNECT_TIMEOUT_MS = 10_000;
    private static final int READ_TIMEOUT_MS = 10 * 60_000;

    public ResultFetcher() {
    }

    public String fetchResults(URI endpoint) {
        return fetchResults(endpoint, Map.of());
    }

    public String fetchResults(URI endpoint, Map<String, String> headers) {
        try {
            return get(endpoint, headers);
        } catch (Exception e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new RuntimeException("Failed to fetch results from " + endpoint
                    + " (" + e.getClass().getSimpleName() + ": " + e.getMessage() + ")", e);
        }
    }

    private String get(URI endpoint, Map<String, String> headers) throws IOException {
        int port = endpoint.getPort() >= 0 ? endpoint.getPort() : 80;
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(endpoint.getHost(), port), CONNECT_TIMEOUT_MS);
            socket.setSoTimeout(READ_TIMEOUT_MS);
            String path = endpoint.getRawPath().isEmpty() ? "/" : endpoint.getRawPath();
            if (endpoint.getRawQuery() != null) {
                path += "?" + endpoint.getRawQuery();
            }
            StringBuilder request = new StringBuilder("GET " + path + " HTTP/1.1\r\nHost: " + endpoint.getHost() + ":" + port
                    + "\r\nConnection: close\r\nAccept: text/xml\r\n");
            // header values come from the caller's request: line breaks are removed so they cannot add headers
            headers.forEach((name, value) -> request.append(name.replaceAll("[\\r\\n:]", "")).append(": ")
                    .append(value.replaceAll("[\\r\\n]", "")).append("\r\n"));
            socket.getOutputStream().write(request.append("\r\n").toString().getBytes(StandardCharsets.US_ASCII));
            socket.getOutputStream().flush();

            InputStream in = new BufferedInputStream(socket.getInputStream());
            String statusLine = readLine(in);
            String[] status = statusLine.split(" ", 3);
            if (status.length < 2 || !status[0].startsWith("HTTP/")) {
                throw new IOException("Invalid HTTP response: " + statusLine);
            }
            int code = Integer.parseInt(status[1]);
            boolean chunked = false;
            long contentLength = -1;
            for (String line = readLine(in); !line.isEmpty(); line = readLine(in)) {
                String lower = line.toLowerCase(Locale.ROOT);
                if (lower.startsWith("transfer-encoding:") && lower.contains("chunked")) {
                    chunked = true;
                } else if (lower.startsWith("content-length:")) {
                    contentLength = Long.parseLong(line.substring(line.indexOf(':') + 1).trim());
                }
            }
            if (code < 200 || code >= 300) {
                throw new IOException("HTTP " + code);
            }
            byte[] body = chunked ? readChunked(in) : contentLength >= 0 ? in.readNBytes((int) contentLength) : in.readAllBytes();
            return new String(body, StandardCharsets.UTF_8);
        }
    }

    private static byte[] readChunked(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        while (true) {
            String sizeLine = readLine(in);
            int ext = sizeLine.indexOf(';');
            int size = Integer.parseInt((ext >= 0 ? sizeLine.substring(0, ext) : sizeLine).trim(), 16);
            if (size == 0) {
                return out.toByteArray();
            }
            byte[] chunk = in.readNBytes(size);
            if (chunk.length < size) {
                throw new IOException("Truncated chunk");
            }
            out.write(chunk);
            readLine(in);
        }
    }

    private static String readLine(InputStream in) throws IOException {
        StringBuilder sb = new StringBuilder();
        for (int c = in.read(); c != '\n'; c = in.read()) {
            if (c < 0) {
                throw new IOException("Unexpected end of stream");
            }
            if (c != '\r') {
                sb.append((char) c);
            }
        }
        return sb.toString();
    }
}
