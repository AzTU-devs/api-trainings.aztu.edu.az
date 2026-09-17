package com.eduplatform.eduplatform_backend.common.security.config;

import com.eduplatform.eduplatform_backend.common.security.RestAccessDeniedHandler;
import com.eduplatform.eduplatform_backend.common.security.RestAuthEntryPoint;
import com.eduplatform.eduplatform_backend.common.security.filter.JwtAuthFilter;
import com.eduplatform.eduplatform_backend.common.security.oauth.OAuth2LoginSuccessHandler;
import com.eduplatform.eduplatform_backend.common.security.ratelimit.AuthRateLimitFilter;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfigurationSource;

import org.springframework.http.HttpStatus;

@Configuration
@EnableMethodSecurity(prePostEnabled = true)
public class SecurityConfig {

    @Bean
    SecurityFilterChain filterChain(
            HttpSecurity http,
            CorsConfigurationSource cors,
            JwtAuthFilter jwtAuthFilter,
            AuthRateLimitFilter rateLimitFilter,
            RestAuthEntryPoint authEntryPoint,
            RestAccessDeniedHandler accessDeniedHandler,
            ObjectProvider<ClientRegistrationRepository> clientRegProvider,
            ObjectProvider<OAuth2LoginSuccessHandler> oauthSuccessProvider
    ) throws Exception {

        http
            .cors(c -> c.configurationSource(cors))
            .csrf(AbstractHttpConfigurer::disable)
            // No HTTP Basic, no form login — anything that would prompt a browser.
            .httpBasic(AbstractHttpConfigurer::disable)
            .formLogin(AbstractHttpConfigurer::disable)
            // Stop Spring from trying to authenticate anonymous browser navigations.
            .anonymous(AbstractHttpConfigurer::disable)
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                    // Health & docs. /actuator/health/** covers the liveness and readiness
                    // probes — without the wildcard they 401 and every orchestrator that
                    // uses them (Kubernetes, ECS) treats the container as failed.
                    .requestMatchers("/actuator/health", "/actuator/health/**",
                            "/actuator/info", "/actuator/prometheus").permitAll()
                    .requestMatchers("/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html").permitAll()
                    // Auth endpoints (open)
                    .requestMatchers(HttpMethod.POST,
                            "/api/auth/register",
                            "/api/auth/register/tutor/start",
                            "/api/auth/register/tutor/verify",
                            "/api/auth/login",
                            "/api/auth/refresh",
                            "/api/auth/admin/register/start",
                            "/api/auth/admin/register/verify",
                            "/api/auth/password/forgot",
                            "/api/auth/password/reset",
                            "/api/auth/email/verify/confirm").permitAll()
                    .requestMatchers("/api/auth/oauth/**").permitAll()
                    // WebSocket handshake — STOMP CONNECT enforces JWT at the message layer
                    .requestMatchers("/ws", "/ws/**").permitAll()
                    // Public catalog browsing
                    .requestMatchers(HttpMethod.GET, "/api/public/**").permitAll()
                    // Pre-flight
                    .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                    // Everything else needs a JWT
                    .anyRequest().authenticated())
            .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class)
            // Ahead of the JWT filter, so a throttled auth request is rejected before any
            // credential is checked and therefore cannot spend a victim's failed-login budget.
            .addFilterBefore(rateLimitFilter, JwtAuthFilter.class);

        // OAuth2 login (Google/Facebook) is wired only when client credentials are configured.
        // Apple uses a separate custom controller at /api/auth/oauth/apple/**.
        ClientRegistrationRepository clientReg = clientRegProvider.getIfAvailable();
        OAuth2LoginSuccessHandler oauthSuccess = oauthSuccessProvider.getIfAvailable();
        if (clientReg != null && oauthSuccess != null
                && OAuthClientRegistrationConfig.hasProviders(clientReg)) {
            http.oauth2Login(o -> o
                    .clientRegistrationRepository(clientReg)
                    .successHandler(oauthSuccess)
                    // Default endpoints stay at /oauth2/authorization/{provider} and /login/oauth2/code/{provider}.
                    // The frontend should redirect users to /oauth2/authorization/google to start the flow.
                    .failureHandler((req, res, ex) -> {
                        res.setStatus(HttpStatus.UNAUTHORIZED.value());
                        res.setContentType("application/json");
                        res.getWriter().write(
                            "{\"status\":401,\"code\":\"OAUTH_LOGIN_FAILED\",\"message\":\""
                            + ex.getMessage().replace("\"", "'") + "\"}");
                    }));
        }

        // Exception handling MUST be configured AFTER oauth2Login, otherwise oauth2Login
        // installs its own LoginUrlAuthenticationEntryPoint that redirects browser
        // requests to a built-in /login HTML page.
        http.exceptionHandling(e -> e
                .authenticationEntryPoint(authEntryPoint)
                .defaultAuthenticationEntryPointFor(
                        new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED),
                        request -> true)
                .accessDeniedHandler(accessDeniedHandler));

        return http.build();
    }
}
