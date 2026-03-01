package com.schemaguard.model;

import com.fasterxml.jackson.annotation.JsonFormat;

import java.time.OffsetDateTime;

/**
 * Canonical error response payload returned for all API errors.
 *
 * Shape:
 * {
 *   "timestamp": "2026-02-28T10:15:30Z",
 *   "status": 400,
 *   "error": "Bad Request",
 *   "message": "...",
 *   "path": "/api/v1/plan"
 * }
 */
public class ApiError {

    @JsonFormat(shape = JsonFormat.Shape.STRING)
    private final OffsetDateTime timestamp;
    private final int status;
    private final String error;
    private final String message;
    private final String path;

    public ApiError(int status, String error, String message, String path) {
        this.timestamp = OffsetDateTime.now();
        this.status = status;
        this.error = error;
        this.message = message;
        this.path = path;
    }

    public OffsetDateTime getTimestamp() { return timestamp; }
    public int getStatus()               { return status; }
    public String getError()             { return error; }
    public String getMessage()           { return message; }
    public String getPath()              { return path; }
}
