package com.eduplatform.eduplatform_backend.common.error;

import org.springframework.http.HttpStatus;

/**
 * Base for every domain exception. {@link #status} drives the HTTP status,
 * {@link #code} is a stable machine identifier for clients ({@code USER_NOT_FOUND}, etc.).
 */
public class AppException extends RuntimeException {

    private final HttpStatus status;
    private final String code;

    public AppException(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public HttpStatus status() { return status; }
    public String code()       { return code; }
}
