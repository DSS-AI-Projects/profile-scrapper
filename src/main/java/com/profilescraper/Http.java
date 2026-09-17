package com.profilescraper;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;

/**
 * Minimal blocking HTTP over {@link HttpURLConnection}.
 *
 * <p>Deliberately not {@code java.net.http.HttpClient}. Constructing that opens an NIO
 * {@link java.nio.channels.Selector}, whose wakeup pipe the JDK implements with a
 * Unix-domain-socket loopback connect. On hosts whose network stack rejects that call, every
 * request fails before it is even sent, with {@code UncheckedIOException: Unable to establish
 * loopback connection} — and there is no system property to turn the behaviour off.
 * {@code HttpURLConnection} polls the socket directly and has no such dependency.
 *
 * <p>Nothing here needs what {@code HttpClient} offers over it: these are one-shot
 * request/response calls, with no use of HTTP/2, async, or connection pooling.
 */
public final class Http {

    /** An HTTP status and its body, whether or not the status indicates success. */
    public record Response(int status, String body) {
        public boolean isSuccess() {
            return status >= 200 && status < 300;
        }
    }

    private Http() {
    }

    public static Response get(String url, int connectTimeoutMs, int readTimeoutMs)
            throws IOException {
        return send(url, "GET", null, null, connectTimeoutMs, readTimeoutMs);
    }

    public static Response post(String url, String contentType, String body,
                                int connectTimeoutMs, int readTimeoutMs) throws IOException {
        return send(url, "POST", contentType, body, connectTimeoutMs, readTimeoutMs);
    }

    private static Response send(String url, String method, String contentType, String body,
                                 int connectTimeoutMs, int readTimeoutMs) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) URI.create(url).toURL().openConnection();
        try {
            connection.setRequestMethod(method);
            connection.setConnectTimeout(connectTimeoutMs);
            connection.setReadTimeout(readTimeoutMs);
            if (contentType != null) {
                connection.setRequestProperty("Content-Type", contentType);
            }
            if (body != null) {
                connection.setDoOutput(true);
                try (OutputStream out = connection.getOutputStream()) {
                    out.write(body.getBytes(StandardCharsets.UTF_8));
                }
            }
            int status = connection.getResponseCode();
            return new Response(status, readBody(connection, status));
        } finally {
            connection.disconnect();
        }
    }

    /** Error responses expose their body on the error stream, not the input stream. */
    private static String readBody(HttpURLConnection connection, int status) throws IOException {
        InputStream source = status >= 400 ? connection.getErrorStream() : connection.getInputStream();
        if (source == null) return "";
        try (InputStream in = source) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
