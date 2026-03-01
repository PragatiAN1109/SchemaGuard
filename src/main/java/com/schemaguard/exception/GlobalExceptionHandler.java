package com.schemaguard.exception;

import com.schemaguard.model.ApiError;
import com.schemaguard.validation.SchemaValidationException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Single global exception handler that maps every thrown exception to the
 * canonical ApiError JSON contract:
 *
 *   { "timestamp", "status", "error", "message", "path" }
 *
 * No stack traces are ever included in the response body.
 * Spring Security auth errors are handled separately via SecurityErrorHandler.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    // ── 400 Bad Request — JSON Schema validation failure ──────────────────────
    @ExceptionHandler(SchemaValidationException.class)
    public ResponseEntity<ApiError> handleSchemaValidation(
            SchemaValidationException ex, HttpServletRequest req) {

        String detail = ex.getErrors() != null && !ex.getErrors().isEmpty()
                ? ex.getMessage() + " — " + String.join("; ", ex.getErrors())
                : ex.getMessage();

        return build(HttpStatus.BAD_REQUEST, detail, req);
    }

    // ── 400 Bad Request — Malformed / unparseable JSON body ───────────────────
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiError> handleJsonParse(
            HttpMessageNotReadableException ex, HttpServletRequest req) {

        String cause = ex.getMostSpecificCause() != null
                ? ex.getMostSpecificCause().getMessage()
                : ex.getMessage();
        return build(HttpStatus.BAD_REQUEST, "Malformed JSON request body: " + cause, req);
    }

    // ── 404 Not Found ─────────────────────────────────────────────────────────
    @ExceptionHandler(NotFoundException.class)
    public ResponseEntity<ApiError> handleNotFound(
            NotFoundException ex, HttpServletRequest req) {
        return build(HttpStatus.NOT_FOUND, ex.getMessage(), req);
    }

    // ── 409 Conflict ──────────────────────────────────────────────────────────
    @ExceptionHandler(ConflictException.class)
    public ResponseEntity<ApiError> handleConflict(
            ConflictException ex, HttpServletRequest req) {
        return build(HttpStatus.CONFLICT, ex.getMessage(), req);
    }

    // ── 412 Precondition Failed ───────────────────────────────────────────────
    @ExceptionHandler(PreconditionFailedException.class)
    public ResponseEntity<ApiError> handlePreconditionFailed(
            PreconditionFailedException ex, HttpServletRequest req) {
        return build(HttpStatus.PRECONDITION_FAILED, ex.getMessage(), req);
    }

    // ── 500 Internal Server Error — catch-all (no stack trace in body) ────────
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> handleGeneric(
            Exception ex, HttpServletRequest req) {
        // Log the real cause server-side; return a clean message to the client
        return build(HttpStatus.INTERNAL_SERVER_ERROR, "An unexpected error occurred", req);
    }

    // ── Shared builder ────────────────────────────────────────────────────────
    private ResponseEntity<ApiError> build(HttpStatus status, String message, HttpServletRequest req) {
        ApiError body = new ApiError(
                status.value(),
                status.getReasonPhrase(),
                message,
                req.getRequestURI()
        );
        return ResponseEntity.status(status).body(body);
    }
}
