package com.schemaguard.model;

import java.util.List;

public class ErrorResponse {
    private final String error;
    private final String message;
    private final List<String> details;

    public ErrorResponse(String error, String message, List<String> details) {
        this.error = error;
        this.message = message;
        this.details = details;
    }

    public String getError() { return error; }
    public String getMessage() { return message; }
    public List<String> getDetails() { return details; }
}