package com.schemaguard.config;

import com.schemaguard.security.SecurityErrorHandler;
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
 * Security error handling is overridden via SecurityErrorHandler so that
 * 401 and 403 responses follow the same ApiError JSON contract as the rest
 * of the API (no HTML, no Spring Security default response format).
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    private static final String GOOGLE_JWK_SET_URI = "https://www.googleapis.com/oauth2/v3/certs";
    private static final String GOOGLE_ISSUER      = "https://accounts.google.com";

    @Value("${google.client-id}")
    private String googleClientId;

    private final SecurityErrorHandler securityErrorHandler;

    public SecurityConfig(SecurityErrorHandler securityErrorHandler) {
        this.securityErrorHandler = securityErrorHandler;
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
            .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .csrf(csrf -> csrf.disable())

            // Override Spring Security default error responses with ApiError JSON
            .exceptionHandling(ex -> ex
                .authenticationEntryPoint(securityErrorHandler)   // 401
                .accessDeniedHandler(securityErrorHandler)         // 403
            )

            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/api/v1/schema/**").permitAll()
                .requestMatchers("/api/v1/plan/**").authenticated()
                .anyRequest().authenticated()
            )

            .oauth2ResourceServer(oauth2 -> oauth2
                .jwt(jwt -> jwt.decoder(jwtDecoder()))
                // Also override the entry point inside the resource-server DSL
                // so that token validation failures (bad sig, expired) hit our handler
                .authenticationEntryPoint(securityErrorHandler)
            );

        return http.build();
    }

    @Bean
    public JwtDecoder jwtDecoder() {
        NimbusJwtDecoder decoder = NimbusJwtDecoder
                .withJwkSetUri(GOOGLE_JWK_SET_URI)
                .build();

        OAuth2TokenValidator<Jwt> issuerValidator =
                JwtValidators.createDefaultWithIssuer(GOOGLE_ISSUER);

        OAuth2TokenValidator<Jwt> audienceValidator =
                new JwtClaimValidator<List<String>>(JwtClaimNames.AUD,
                        aud -> aud != null && aud.contains(googleClientId));

        decoder.setJwtValidator(
                new DelegatingOAuth2TokenValidator<>(issuerValidator, audienceValidator));

        return decoder;
    }
}
