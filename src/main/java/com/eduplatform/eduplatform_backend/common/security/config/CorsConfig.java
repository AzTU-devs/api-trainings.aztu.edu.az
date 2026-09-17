package com.eduplatform.eduplatform_backend.common.security.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.List;

@Configuration
public class CorsConfig {

    /**
     * Marked {@link Primary} to disambiguate from Spring MVC's auto-configured
     * {@code mvcHandlerMappingIntrospector} which also exposes a {@link CorsConfigurationSource}.
     */
    @Bean
    @Primary
    CorsConfigurationSource corsConfigurationSource(CorsProperties props) {
        CorsConfiguration cfg = new CorsConfiguration();
        // Patterns, not origins: allowCredentials(true) below makes Spring reject the
        // literal "*" in setAllowedOrigins — and it throws per-request, not at startup,
        // so the app boots healthy and then 500s on the first browser call.
        // setAllowedOriginPatterns accepts "*", wildcards like https://*.example.com,
        // and plain exact origins, so narrowing CORS later is an env-var change only.
        cfg.setAllowedOriginPatterns(props.allowedOrigins() == null ? List.of() : props.allowedOrigins());
        cfg.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        cfg.setAllowedHeaders(List.of("*"));
        // Retry-After is exposed because the auth rate limiter sends it with every 429, and a
        // cross-origin caller cannot read a response header that is not listed here — the
        // login form would know it was throttled but not for how long.
        cfg.setExposedHeaders(List.of("Authorization", "X-Request-Id", "Retry-After"));
        cfg.setAllowCredentials(true);
        cfg.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource src = new UrlBasedCorsConfigurationSource();
        src.registerCorsConfiguration("/api/**", cfg);
        return src;
    }
}
