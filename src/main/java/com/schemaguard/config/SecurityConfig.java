package com.schemaguard.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimNames;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.web.SecurityFilterChain;

import java.util.List;

/**
 * OAuth2 Resource Server security configuration.
 *
 * All /api/v1/plan/** endpoints require a valid Google RS256 Bearer token.
 * GET /api/v1/schema/** is public — no authentication required.
 *
 * Token validation:
 * - Signature verified against Google's JWK Set (RS256)
 * - Issuer must be https://accounts.google.com
 * - Audience must contain the configured Google Client ID (GOOGLE_CLIENT_ID env var)
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    // Google's JWK Set URI — used to fetch public keys for RS256 signature verification
    private static final String GOOGLE_JWK_SET_URI = "https://www.googleapis.com/oauth2/v3/certs";

    // Google issues tokens with either of these issuers
    private static final String GOOGLE_ISSUER = "https://accounts.google.com";

    @Value("${google.client-id}")
    private String googleClientId;

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
            // Stateless REST API — no sessions
            .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))

            // Disable CSRF (not needed for stateless token-based API)
            .csrf(csrf -> csrf.disable())

            // Route authorization rules
            .authorizeHttpRequests(auth -> auth
                // Schema endpoint is public — no token required
                .requestMatchers("/api/v1/schema/**").permitAll()
                // All plan endpoints require a valid Bearer token
                .requestMatchers("/api/v1/plan/**").authenticated()
                // Everything else requires auth by default
                .anyRequest().authenticated()
            )

            // Configure as OAuth2 Resource Server using JWT
            .oauth2ResourceServer(oauth2 -> oauth2
                .jwt(jwt -> jwt.decoder(jwtDecoder()))
            );

        return http.build();
    }

    /**
     * Custom JwtDecoder that validates:
     * 1. RS256 signature via Google's JWK Set
     * 2. Issuer: https://accounts.google.com
     * 3. Audience: must contain the configured Google Client ID
     */
    @Bean
    public JwtDecoder jwtDecoder() {
        NimbusJwtDecoder decoder = NimbusJwtDecoder
                .withJwkSetUri(GOOGLE_JWK_SET_URI)
                .build();

        // Issuer validator
        OAuth2TokenValidator<Jwt> issuerValidator =
                JwtValidators.createDefaultWithIssuer(GOOGLE_ISSUER);

        // Audience validator — token must contain our Google Client ID in the 'aud' claim
        OAuth2TokenValidator<Jwt> audienceValidator =
                new JwtClaimValidator<List<String>>(JwtClaimNames.AUD,
                        aud -> aud != null && aud.contains(googleClientId));

        // Combine both validators
        decoder.setJwtValidator(
                new DelegatingOAuth2TokenValidator<>(issuerValidator, audienceValidator));

        return decoder;
    }
}
