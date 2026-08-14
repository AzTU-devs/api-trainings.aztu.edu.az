package com.eduplatform.eduplatform_backend.common.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.servers.Server;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

@Configuration
public class OpenApiConfig {

    @Bean
    OpenAPI eduPlatformOpenApi(@Value("${app.base-url}") String baseUrl) {
        final String bearerName = "bearerAuth";
        return new OpenAPI()
                .info(new Info()
                        .title("EduPlatform API")
                        .version("v1")
                        .description("University learning platform — public site + portal backend")
                        .license(new License().name("Proprietary")))
                .servers(List.of(new Server().url(baseUrl).description("default")))
                .addSecurityItem(new SecurityRequirement().addList(bearerName))
                .components(new Components().addSecuritySchemes(bearerName,
                        new SecurityScheme()
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("bearer")
                                .bearerFormat("JWT")
                                .description("Paste the access token returned by /api/auth/login")));
    }
}
