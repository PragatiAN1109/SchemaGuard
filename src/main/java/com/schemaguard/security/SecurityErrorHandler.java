package com.schemaguard.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.schemaguard.model.ApiError;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * Forces Spring Security authentication and authorization errors into the
 * same canonical ApiError JSON contract used by GlobalExceptionHandler.
 *
 * Without this, Spring Security returns its own plain-text or HTML error
 * responses that do not match the API contract.
 *
 * - AuthenticationEntryPoint: invoked when a request reaches a protected
 *   endpoint with no valid Bearer token (missing token, expired, bad sig).
 *   Returns 401 Unauthorized.
 *
 * - AccessDeniedHandler: invoked when an authenticated principal lacks the
 *   required authority for a resource.
 *   Returns 403 Forbidden.
 */
@Component
public class SecurityErrorHandler
        implements AuthenticationEntryPoint, AccessDeniedHandler {

    private final ObjectMapper mapper;

    public SecurityErrorHandler(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    /** 401 — missing or invalid Bearer token */
    @Override
    public void commence(HttpServletRequest req,
                         HttpServletResponse res,
                         AuthenticationException ex) throws IOException {
        writeError(req, res, HttpStatus.UNAUTHORIZED,
                "Authentication required: missing or invalid Bearer token");
    }

    /** 403 — authenticated but not authorised */
    @Override
    public void handle(HttpServletRequest req,
                       HttpServletResponse res,
                       AccessDeniedException ex) throws IOException {
        writeError(req, res, HttpStatus.FORBIDDEN,
                "Access denied: insufficient permissions");
    }

    private void writeError(HttpServletRequest req,
                             HttpServletResponse res,
                             HttpStatus status,
                             String message) throws IOException {
        ApiError body = new ApiError(
                status.value(),
                status.getReasonPhrase(),
                message,
                req.getRequestURI()
        );
        res.setStatus(status.value());
        res.setContentType(MediaType.APPLICATION_JSON_VALUE);
        mapper.writeValue(res.getOutputStream(), body);
    }
}
