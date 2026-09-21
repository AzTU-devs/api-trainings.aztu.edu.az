package com.eduplatform.eduplatform_backend.support;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.MissingNode;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Drives the API over a real socket with a RestClient on the JDK's HttpClient.
 *
 * <p>Not TestRestTemplate: with no Apache HttpClient on the test classpath it falls back to
 * HttpURLConnection, which cannot send PATCH, the verb course edits use. Not MockMvc: two of the
 * behaviours under test live outside Spring MVC. The client address comes from Tomcat's
 * RemoteIpValve, and the refresh cookie and the error dispatch go through the servlet container
 * exactly as they do behind nginx.
 *
 * <p>Every status comes back as a {@link Response} instead of an exception, bodies are written as
 * raw bytes so a test can send a Content-Type the client itself would refuse to parse, and
 * cookies are never stored: a test that needs one reads it from {@link Response#setCookie} and
 * sends it back explicitly, so which cookie travels on which request is visible in the test.
 */
public final class ApiClient {

    private final RestClient rest;
    private final String baseUrl;
    private final ObjectMapper json;

    public ApiClient(int port, ObjectMapper json) {
        HttpClient http = HttpClient.newBuilder()
                // HTTP/1.1 because the JDK client otherwise tries an h2c upgrade on plain http,
                // which is not what nginx or the browsers send to this API.
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(http);
        requestFactory.setReadTimeout(Duration.ofSeconds(30));
        this.rest = RestClient.builder().requestFactory(requestFactory).build();
        this.baseUrl = "http://localhost:" + port;
        this.json = json;
    }

    public Call get(String path) {
        return new Call(HttpMethod.GET, path);
    }

    public Call post(String path) {
        return new Call(HttpMethod.POST, path);
    }

    public Call put(String path) {
        return new Call(HttpMethod.PUT, path);
    }

    public Call patch(String path) {
        return new Call(HttpMethod.PATCH, path);
    }

    public Call delete(String path) {
        return new Call(HttpMethod.DELETE, path);
    }

    /** One request being assembled. Nothing is sent until {@link #send()}. */
    public final class Call {

        private final HttpMethod method;
        private final String path;
        private final StringBuilder query = new StringBuilder();
        private final Map<String, String> headers = new LinkedHashMap<>();
        private byte[] body;

        private Call(HttpMethod method, String path) {
            this.method = method;
            this.path = path;
        }

        /** Adds a URL-encoded query parameter; an empty string is sent as {@code name=}. */
        public Call query(String name, Object value) {
            query.append(query.isEmpty() ? '?' : '&')
                    .append(URLEncoder.encode(name, StandardCharsets.UTF_8))
                    .append('=')
                    .append(URLEncoder.encode(String.valueOf(value), StandardCharsets.UTF_8));
            return this;
        }

        public Call header(String name, String value) {
            headers.put(name, value);
            return this;
        }

        public Call bearer(String accessToken) {
            return header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken);
        }

        public Call cookie(String name, String value) {
            return header(HttpHeaders.COOKIE, name + "=" + value);
        }

        /** Serialises {@code payload} with the application's own ObjectMapper. */
        public Call json(Object payload) {
            try {
                body = json.writeValueAsBytes(payload);
            } catch (JsonProcessingException e) {
                throw new IllegalArgumentException("Cannot serialise request body", e);
            }
            return header(HttpHeaders.CONTENT_TYPE, "application/json");
        }

        /** A body sent verbatim under whatever Content-Type the test chooses, valid or not. */
        public Call body(String contentType, String raw) {
            body = raw.getBytes(StandardCharsets.UTF_8);
            return header(HttpHeaders.CONTENT_TYPE, contentType);
        }

        /** A multipart/form-data body holding a single file part. */
        public Call file(String field, String filename, String contentType, byte[] content) {
            String boundary = "eduplatform-test-" + UUID.randomUUID();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            writeAscii(out, "--" + boundary + "\r\n"
                    + "Content-Disposition: form-data; name=\"" + field + "\"; filename=\"" + filename + "\"\r\n"
                    + "Content-Type: " + contentType + "\r\n\r\n");
            out.writeBytes(content);
            writeAscii(out, "\r\n--" + boundary + "--\r\n");
            body = out.toByteArray();
            return header(HttpHeaders.CONTENT_TYPE, "multipart/form-data; boundary=" + boundary);
        }

        public Response send() {
            String request = method + " " + path + query;
            // A URI, not a template: the path is sent exactly as written (;jsessionid included) and
            // the query is already encoded.
            RestClient.RequestBodySpec spec = rest.method(method)
                    .uri(URI.create(baseUrl + path + query))
                    .headers(h -> headers.forEach(h::set));
            if (body != null) {
                byte[] content = body;
                // Written as a raw stream, which bypasses the message converters: they would parse
                // the Content-Type header, and refuse the malformed one a test sends on purpose.
                spec = spec.contentLength(content.length).body(out -> out.write(content));
            }
            return spec.exchange((clientRequest, response) -> {
                byte[] bytes = response.getBody().readAllBytes();
                HttpHeaders responseHeaders = new HttpHeaders();
                responseHeaders.putAll(response.getHeaders());
                return new Response(request, response.getStatusCode().value(), responseHeaders, bytes, parse(bytes));
            });
        }

        private JsonNode parse(byte[] raw) {
            if (raw == null || raw.length == 0) return MissingNode.getInstance();
            try {
                return json.readTree(raw);
            } catch (IOException notJson) {
                // Media content, Tomcat's own HTML error page and the like: the test reads bytes().
                return MissingNode.getInstance();
            }
        }

        private static void writeAscii(ByteArrayOutputStream out, String text) {
            out.writeBytes(text.getBytes(StandardCharsets.US_ASCII));
        }
    }

    /**
     * A received response. {@code json} is {@link MissingNode} when the body is empty or not JSON,
     * so navigation with {@code path(...)} never throws.
     */
    public record Response(String request, int status, HttpHeaders headers, byte[] bytes, JsonNode json) {

        /** The {@code data} member of the success envelope ({@code ApiResponse}). */
        public JsonNode data() {
            return json.path("data");
        }

        /** The machine-readable {@code code} of an {@code ApiError} body, or null. */
        public String code() {
            JsonNode code = json.path("code");
            return code.isTextual() ? code.asText() : null;
        }

        public String body() {
            return bytes == null ? "" : new String(bytes, StandardCharsets.UTF_8);
        }

        public Optional<String> header(String name) {
            return Optional.ofNullable(headers.getFirst(name));
        }

        /** The full Set-Cookie header for {@code name}, attributes included. */
        public Optional<String> setCookie(String name) {
            return headers.getOrEmpty(HttpHeaders.SET_COOKIE).stream()
                    .filter(value -> value.startsWith(name + "="))
                    .findFirst();
        }

        /**
         * Fails with the request line and the response body, which is what explains an unexpected
         * status; a bare "expected 200 but was 500" does not.
         */
        public Response expectStatus(int expected) {
            if (status != expected) {
                throw new AssertionError(request + ": expected HTTP " + expected + " but got " + status
                        + "\n" + body());
            }
            return this;
        }

        public Response expectError(int expectedStatus, String expectedCode) {
            expectStatus(expectedStatus);
            if (!expectedCode.equals(code())) {
                throw new AssertionError(request + ": expected error code " + expectedCode + " but got "
                        + code() + "\n" + body());
            }
            return this;
        }
    }
}
