package com.eduplatform.eduplatform_backend.common.security.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Bound from {@code app.security.jwt.*} in application.properties. */
@ConfigurationProperties(prefix = "app.security.jwt")
public record JwtProperties(
        String issuer,
        String accessSecret,
        int accessTtlMinutes,
        int refreshTtlDays
) {}
