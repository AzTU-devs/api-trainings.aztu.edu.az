package com.eduplatform.eduplatform_backend.common.security.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties({
        JwtProperties.class,
        AppSecurityProperties.class,
        CorsProperties.class,
        OAuthProperties.class
})
public class PropertiesConfig {
}
