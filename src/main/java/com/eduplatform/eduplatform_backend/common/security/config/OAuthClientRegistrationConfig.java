package com.eduplatform.eduplatform_backend.common.security.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.oauth2.client.CommonOAuth2Provider;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository;

import java.util.ArrayList;
import java.util.List;

/**
 * Registers Google and Facebook OAuth2 clients only when their credentials are configured.
 *
 * Spring Boot's {@code spring-boot-starter-oauth2-client} auto-config always wires an
 * {@code OAuth2AuthorizedClientManager} that requires <em>some</em> {@link ClientRegistrationRepository}
 * bean — so we always publish one. When no providers are configured we publish an empty stub;
 * {@link SecurityConfig} uses {@link #hasProviders} to decide whether to wire {@code oauth2Login}.
 */
@Configuration
public class OAuthClientRegistrationConfig {

    @Bean
    ClientRegistrationRepository clientRegistrationRepository(OAuthProperties props) {
        List<ClientRegistration> regs = new ArrayList<>();

        if (hasText(props.google().clientId()) && hasText(props.google().clientSecret())) {
            regs.add(CommonOAuth2Provider.GOOGLE.getBuilder("google")
                    .clientId(props.google().clientId())
                    .clientSecret(props.google().clientSecret())
                    .redirectUri(props.google().redirectUri())
                    .scope("openid", "email", "profile")
                    .build());
        }

        if (hasText(props.facebook().appId()) && hasText(props.facebook().appSecret())) {
            regs.add(CommonOAuth2Provider.FACEBOOK.getBuilder("facebook")
                    .clientId(props.facebook().appId())
                    .clientSecret(props.facebook().appSecret())
                    .redirectUri(props.facebook().redirectUri())
                    .scope("email", "public_profile")
                    .userInfoUri("https://graph.facebook.com/me?fields=id,name,email,picture")
                    .build());
        }

        return regs.isEmpty()
                ? new EmptyClientRegistrationRepository()
                : new InMemoryClientRegistrationRepository(regs);
    }

    /** True when at least one OAuth provider is configured. */
    public static boolean hasProviders(ClientRegistrationRepository repo) {
        return !(repo instanceof EmptyClientRegistrationRepository);
    }

    private static boolean hasText(String s) {
        return s != null && !s.isBlank();
    }

    /** Stub used when no providers are configured. Satisfies Spring's bean requirement without any clients. */
    public static final class EmptyClientRegistrationRepository implements ClientRegistrationRepository {
        @Override
        public ClientRegistration findByRegistrationId(String registrationId) {
            return null;
        }
    }
}
