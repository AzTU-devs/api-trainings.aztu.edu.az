package com.eduplatform.eduplatform_backend.common.error;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;

/**
 * Base for every domain exception. {@link #status} drives the HTTP status,
 * {@link #code} is a stable machine identifier for clients ({@code USER_NOT_FOUND}, etc.).
 * {@link #headers} carries the few response headers an error has to set, such as the
 * {@code Retry-After} of a temporary lockout; it is empty for almost every error.
 */
public class AppException extends RuntimeException {

    private final HttpStatus status;
    private final String code;
    private final HttpHeaders headers;

    public AppException(HttpStatus status, String code, String message) {
        this(status, code, message, HttpHeaders.EMPTY);
    }

    public AppException(HttpStatus status, String code, String message, HttpHeaders headers) {
        super(message);
        this.status = status;
        this.code = code;
        this.headers = headers == null ? HttpHeaders.EMPTY : HttpHeaders.readOnlyHttpHeaders(headers);
    }

    public HttpStatus status() { return status; }
    public String code()       { return code; }
    public HttpHeaders headers() { return headers; }
}
