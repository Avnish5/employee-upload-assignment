package com.example.employees.web;

import org.springframework.http.HttpStatus;

public class ApiException extends RuntimeException {

    private final HttpStatus status;
    private final String code;

    public ApiException(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public static ApiException notFound(String message) {
        return new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", message);
    }

    public static ApiException badFile(String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, "INVALID_FILE", message);
    }

    public HttpStatus status() {
        return status;
    }

    public String code() {
        return code;
    }
}
