package com.eduplatform.eduplatform_backend.common.error;

import org.springframework.http.HttpStatus;

/** Factory helpers for the common error families. */
public final class Errors {

    private Errors() {}

    public static AppException notFound(String code, String message) {
        return new AppException(HttpStatus.NOT_FOUND, code, message);
    }

    public static AppException conflict(String code, String message) {
        return new AppException(HttpStatus.CONFLICT, code, message);
    }

    public static AppException badRequest(String code, String message) {
        return new AppException(HttpStatus.BAD_REQUEST, code, message);
    }

    public static AppException unauthorized(String code, String message) {
        return new AppException(HttpStatus.UNAUTHORIZED, code, message);
    }

    public static AppException forbidden(String code, String message) {
        return new AppException(HttpStatus.FORBIDDEN, code, message);
    }

    public static AppException unprocessable(String code, String message) {
        return new AppException(HttpStatus.UNPROCESSABLE_ENTITY, code, message);
    }
}
