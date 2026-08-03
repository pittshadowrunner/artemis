package com.artemis.wms.common;

import org.springframework.http.HttpStatus;

public class ApiException extends RuntimeException {
    private final HttpStatus status;
    public ApiException(HttpStatus status, String message) { super(message); this.status = status; }
    public HttpStatus status() { return status; }

    public static ApiException badRequest(String msg) { return new ApiException(HttpStatus.BAD_REQUEST, msg); }
    public static ApiException notFound(String msg)   { return new ApiException(HttpStatus.NOT_FOUND, msg); }
    public static ApiException conflict(String msg)   { return new ApiException(HttpStatus.CONFLICT, msg); }
}
