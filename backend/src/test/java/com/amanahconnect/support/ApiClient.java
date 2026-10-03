package com.amanahconnect.support;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** A small real-HTTP client for the running test server: JSON in, JSON out, cookies read from Set-Cookie. */
public final class ApiClient {

    /** The one allowed browser origin in the test profile. */
    public static final String ORIGIN = "http://localhost:5173";

    private static final HttpClient HTTP = HttpClient.newHttpClient();
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final int port;

    public ApiClient(int port) {
        this.port = port;
    }

    public record Response(int status, HttpHeaders headers, String body) {

        public JsonNode json() {
            return JSON.readTree(body);
        }

        public String code() {
            return json().get("code").asString();
        }

        public String header(String name) {
            return headers.firstValue(name).orElse(null);
        }

        public List<String> setCookies() {
            return headers.allValues("set-cookie");
        }

        /** The value of a cookie set by this response, or null. */
        public String cookie(String name) {
            return setCookies().stream()
                    .filter(c -> c.startsWith(name + "="))
                    .map(c -> c.substring(name.length() + 1, c.indexOf(';') < 0 ? c.length() : c.indexOf(';')))
                    .findFirst()
                    .orElse(null);
        }

        public String rawSetCookie(String name) {
            return setCookies().stream().filter(c -> c.startsWith(name + "=")).findFirst().orElse(null);
        }
    }

    public Response post(String path, Object body, String... headers) {
        return send("POST", path, body == null ? null : JSON.writeValueAsString(body), headers);
    }

    /** A response whose body is bytes (a PDF, a PNG): the string form of a binary body would be corrupted. */
    public record BinaryResponse(int status, HttpHeaders headers, byte[] body) {
        public String header(String name) {
            return headers.firstValue(name).orElse(null);
        }
    }

    public BinaryResponse getBytes(String path, String... headers) {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).GET();
        for (int i = 0; i + 1 < headers.length; i += 2) {
            request.header(headers[i], headers[i + 1]);
        }
        try {
            HttpResponse<byte[]> response = HTTP.send(request.build(), HttpResponse.BodyHandlers.ofByteArray());
            return new BinaryResponse(response.statusCode(), response.headers(), response.body());
        } catch (IOException e) {
            throw new IllegalStateException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    public Response get(String path, String... headers) {
        return send("GET", path, null, headers);
    }

    public Response put(String path, Object body, String... headers) {
        return send("PUT", path, body == null ? null : JSON.writeValueAsString(body), headers);
    }

    public Response delete(String path, String... headers) {
        return send("DELETE", path, null, headers);
    }

    /** Any method; used by the cross-tenant harness. */
    public Response call(String method, String path, Object body, String... headers) {
        return send(method, path, body == null ? null : JSON.writeValueAsString(body), headers);
    }

    public Response postRaw(String path, String rawBody, String... headers) {
        return send("POST", path, rawBody, headers);
    }

    /** A multipart/form-data POST: plain text fields and CSV file parts (name -> bytes). */
    public Response postMultipart(String path, java.util.Map<String, String> fields, java.util.Map<String, byte[]> files, String... headers) {
        String boundary = "----amanah" + System.nanoTime();
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        java.util.function.Consumer<String> text = t -> out.writeBytes(t.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        fields.forEach((name, value) -> text.accept("--" + boundary + "\r\nContent-Disposition: form-data; name=\"" + name + "\"\r\n\r\n" + value + "\r\n"));
        files.forEach((name, bytes) -> {
            text.accept("--" + boundary + "\r\nContent-Disposition: form-data; name=\"" + name + "\"; filename=\"" + name + ".csv\"\r\nContent-Type: text/csv\r\n\r\n");
            out.writeBytes(bytes);
            text.accept("\r\n");
        });
        text.accept("--" + boundary + "--\r\n");
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofByteArray(out.toByteArray()));
        for (int i = 0; i + 1 < headers.length; i += 2) {
            request.header(headers[i], headers[i + 1]);
        }
        try {
            HttpResponse<String> response = HTTP.send(request.build(), HttpResponse.BodyHandlers.ofString());
            return new Response(response.statusCode(), response.headers(), response.body());
        } catch (IOException e) {
            throw new IllegalStateException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    public static String bearer(String accessToken) {
        return "Bearer " + accessToken;
    }

    /** Headers the refresh and logout endpoints require, plus the refresh cookie. */
    public static String[] cookieCall(String refreshToken) {
        return new String[] {
            "X-Requested-With", "amanah-web", "Origin", ORIGIN, "Cookie", "amanah_refresh=" + refreshToken
        };
    }

    private Response send(String method, String path, String body, String... headers) {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path));
        if (body != null) {
            request.header("Content-Type", "application/json");
            request.method(method, HttpRequest.BodyPublishers.ofString(body));
        } else {
            request.method(method, method.equals("POST") ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.noBody());
        }
        for (int i = 0; i + 1 < headers.length; i += 2) {
            request.header(headers[i], headers[i + 1]);
        }
        try {
            HttpResponse<String> response = HTTP.send(request.build(), HttpResponse.BodyHandlers.ofString());
            return new Response(response.statusCode(), response.headers(), response.body());
        } catch (IOException e) {
            throw new IllegalStateException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
