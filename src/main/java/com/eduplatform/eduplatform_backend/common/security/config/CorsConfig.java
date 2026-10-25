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
        // Credentials are allowed (below), so every origin listed here can call the API as the
        // signed-in user: exact origins only, the site and the dashboard. Exact matching, not
        // patterns: with setAllowedOriginPatterns a "*" in CORS_ALLOWED_ORIGINS reflected EVERY
        // origin back with credentials, which is what production was found doing. Here a "*"
        // is never a wildcard, and StartupSecurityValidator refuses to start outside dev on a
        // wildcard, "null", plain-http or localhost origin anyway. A request whose Origin is
        // not listed gets 403 from the CORS filter, preflight or not.
        // The refresh-token cookie is guarded separately: see AuthController.cookieToken.
        cfg.setAllowedOrigins(props.allowedOrigins() == null ? List.of() : props.allowedOrigins());
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
