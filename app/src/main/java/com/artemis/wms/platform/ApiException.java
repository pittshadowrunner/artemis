package com.artemis.wms.platform;

import org.springframework.http.HttpStatus;

/** Thrown by services; rendered by {@link GlobalExceptionHandler} as a JSON problem. */
public class ApiException extends RuntimeException {
    private final HttpStatus status;
    public ApiException(HttpStatus status, String message) { super(message); this.status = status; }
    public HttpStatus status() { return status; }
    public static ApiException badRequest(String m) { return new ApiException(HttpStatus.BAD_REQUEST, m); }
    public static ApiException notFound(String m)   { return new ApiException(HttpStatus.NOT_FOUND, m); }
    public static ApiException conflict(String m)   { return new ApiException(HttpStatus.CONFLICT, m); }
    public static ApiException forbidden(String m)  { return new ApiException(HttpStatus.FORBIDDEN, m); }
}
