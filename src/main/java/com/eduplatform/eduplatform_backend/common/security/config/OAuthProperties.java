package com.eduplatform.eduplatform_backend.common.security.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Mirrors {@code app.oauth.*} in application.properties for all three providers.
 * Any blank client-id disables that provider — see {@link OAuthClientRegistrationConfig}.
 */
@ConfigurationProperties(prefix = "app.oauth")
public record OAuthProperties(Google google, Facebook facebook, Apple apple) {

    public record Google(String clientId, String clientSecret, String redirectUri) {}
    public record Facebook(String appId, String appSecret, String redirectUri) {}
    public record Apple(String teamId, String servicesId, String keyId, String privateKey, String redirectUri) {}
}
