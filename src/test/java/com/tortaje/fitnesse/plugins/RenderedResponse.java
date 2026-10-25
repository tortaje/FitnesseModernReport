package com.tortaje.fitnesse.plugins;

import fitnesse.http.Response;
import fitnesse.http.ResponseSender;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/** What a browser would receive for a FitNesse {@link Response}: status, headers and the decoded body. */
final class RenderedResponse {

    final int status;
    final String contentType;
    final String body;
    /** The texts passed to the sender, one entry per write (head, then one per chunk). */
    final List<String> writes;

    private RenderedResponse(int status, String contentType, String body, List<String> writes) {
        this.status = status;
        this.contentType = contentType;
        this.body = body;
        this.writes = writes;
    }

    static RenderedResponse of(Response response) throws Exception {
        return of(response, text -> { });
    }

    /** @param onWrite called as soon as each write reaches the sender, while the response is still being produced */
    static RenderedResponse of(Response response, Consumer<String> onWrite) throws Exception {
        ByteArrayOutputStream raw = new ByteArrayOutputStream();
        List<String> writes = new ArrayList<>();
        response.sendTo(new ResponseSender() {
            @Override
            public synchronized void send(byte[] bytes) throws IOException {
                raw.write(bytes);
                String text = new String(bytes, StandardCharsets.UTF_8);
                writes.add(text);
                onWrite.accept(text);
            }

            @Override
            public void close() {
            }
        });
        byte[] bytes = raw.toByteArray();
        String all = new String(bytes, StandardCharsets.ISO_8859_1);
        int split = all.indexOf("\r\n\r\n");
        String head = all.substring(0, split);
        int status = Integer.parseInt(head.split(" ")[1]);
        String contentType = head.lines().filter(l -> l.toLowerCase().startsWith("content-type:")).map(l -> l.substring(13).trim()).findFirst().orElse(null);
        byte[] bodyBytes = java.util.Arrays.copyOfRange(bytes, split + 4, bytes.length);
        boolean chunked = head.toLowerCase().contains("transfer-encoding: chunked");
        return new RenderedResponse(status, contentType, new String(chunked ? dechunk(bodyBytes) : bodyBytes, StandardCharsets.UTF_8), writes);
    }

    private static byte[] dechunk(byte[] body) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int i = 0;
        while (i < body.length) {
            int eol = indexOfCrlf(body, i);
            int size = Integer.parseInt(new String(body, i, eol - i, StandardCharsets.US_ASCII).trim(), 16);
            if (size == 0) {
                break;
            }
            out.write(body, eol + 2, size);
            i = eol + 2 + size + 2;
        }
        return out.toByteArray();
    }

    private static int indexOfCrlf(byte[] data, int from) {
        for (int i = from; i < data.length - 1; i++) {
            if (data[i] == '\r' && data[i + 1] == '\n') {
                return i;
            }
        }
        throw new IllegalStateException("malformed chunked body");
    }
}
