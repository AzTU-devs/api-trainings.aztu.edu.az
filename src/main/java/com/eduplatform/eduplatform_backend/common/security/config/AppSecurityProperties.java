package com.eduplatform.eduplatform_backend.common.security.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.security")
public record AppSecurityProperties(int bcryptStrength) {}
