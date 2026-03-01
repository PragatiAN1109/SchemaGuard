package com.schemaguard.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Servlet filter that runs after Spring Security's JWT authentication.
 * Extracts 'sub' and 'email' claims from the validated Google JWT and:
 *   1. Logs them for audit/tracing purposes.
 *   2. Adds X-User-Sub and X-User-Email response headers for demo visibility.
 *
 * Only runs when a valid authenticated JWT principal is present.
 * No user data is stored anywhere — purely in-request extraction.
 */
@Component
public class JwtClaimsLogger extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(JwtClaimsLogger.class);

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {

        Authentication auth = SecurityContextHolder.getContext().getAuthentication();

        if (auth != null && auth.getPrincipal() instanceof Jwt jwt) {
            String sub   = jwt.getClaimAsString("sub");
            String email = jwt.getClaimAsString("email");

            // Audit log — visible in app logs during demo
            log.info("Authenticated request: method={} path={} sub={} email={}",
                    request.getMethod(), request.getRequestURI(), sub, email);

            // Add to response headers for demo visibility (no sensitive data risk here)
            if (sub != null)   response.setHeader("X-User-Sub", sub);
            if (email != null) response.setHeader("X-User-Email", email);
        }

        filterChain.doFilter(request, response);
    }
}
