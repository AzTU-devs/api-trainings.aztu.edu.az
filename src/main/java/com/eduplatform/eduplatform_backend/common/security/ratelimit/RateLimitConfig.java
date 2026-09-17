package com.eduplatform.eduplatform_backend.common.security.ratelimit;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wiring for the rate limiter. {@link RateLimitProperties} is enabled here rather than in
 * {@code PropertiesConfig} so the feature stays self-contained.
 */
@Configuration
@EnableConfigurationProperties(RateLimitProperties.class)
public class RateLimitConfig {

    /**
     * Spring Boot registers every {@code Filter} bean in the outer servlet chain as well. The
     * limiter has to run inside the Spring Security chain — after the CORS filter, so a browser can
     * actually read the 429, and immediately before the JWT filter — so this disabled registration
     * exists purely to claim the bean and keep the container from mounting a second copy of it.
     */
    @Bean
    FilterRegistrationBean<AuthRateLimitFilter> authRateLimitFilterRegistration(AuthRateLimitFilter filter) {
        FilterRegistrationBean<AuthRateLimitFilter> registration = new FilterRegistrationBean<>(filter);
        registration.setEnabled(false);
        return registration;
    }
}
