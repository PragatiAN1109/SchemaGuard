package com.schemaguard.config;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;

/**
 * Test-only JwtDecoder that replaces the real Google RS256 decoder.
 *
 * Without this, the app context would fail to start in tests because
 * NimbusJwtDecoder tries to fetch Google's JWK Set URI on startup.
 *
 * This decoder is never actually called during tests that use @WithMockUser
 * because Spring Security short-circuits JWT validation when a mock
 * Authentication is already present in the SecurityContext.
 */
@TestConfiguration
public class TestSecurityConfig {

    @Bean
    @Primary
    public JwtDecoder testJwtDecoder() {
        // Throw on any real invocation — tests must use @WithMockUser instead
        return token -> {
            throw new JwtException("Real JWT decoding is not available in tests. Use @WithMockUser.");
        };
    }
}
