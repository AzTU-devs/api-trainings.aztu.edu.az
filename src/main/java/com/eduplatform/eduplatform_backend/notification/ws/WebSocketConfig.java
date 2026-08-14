package com.eduplatform.eduplatform_backend.notification.ws;

import com.eduplatform.eduplatform_backend.common.security.AuthenticatedPrincipal;
import com.eduplatform.eduplatform_backend.common.security.config.CorsProperties;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.core.Authentication;

import java.security.Principal;

/**
 * STOMP-over-WebSocket endpoint at {@code /ws}.
 *
 * Client flow:
 *   1. Open WebSocket to {@code ws(s)://<host>/ws}.
 *   2. Send STOMP CONNECT with header {@code Authorization: Bearer <jwt>}.
 *   3. Subscribe to {@code /user/queue/notifications} — server pushes new notifications here
 *      using {@link org.springframework.messaging.simp.SimpMessagingTemplate#convertAndSendToUser}.
 */
@Configuration
@EnableWebSocketMessageBroker
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    private final WebSocketAuthChannelInterceptor authInterceptor;
    private final BlockedIpHandshakeInterceptor blockedIpHandshakeInterceptor;
    private final CorsProperties cors;

    public WebSocketConfig(WebSocketAuthChannelInterceptor authInterceptor,
                           BlockedIpHandshakeInterceptor blockedIpHandshakeInterceptor,
                           CorsProperties cors) {
        this.authInterceptor = authInterceptor;
        this.blockedIpHandshakeInterceptor = blockedIpHandshakeInterceptor;
        this.cors = cors;
    }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        String[] origins = cors.allowedOrigins() == null
                ? new String[0]
                : cors.allowedOrigins().toArray(new String[0]);
        registry.addEndpoint("/ws")
                .setAllowedOriginPatterns(origins.length == 0 ? new String[]{"*"} : origins)
                .addInterceptors(blockedIpHandshakeInterceptor)
                .setHandshakeHandler(new org.springframework.web.socket.server.support.DefaultHandshakeHandler() {
                    @Override
                    protected Principal determineUser(
                            org.springframework.http.server.ServerHttpRequest request,
                            org.springframework.web.socket.WebSocketHandler wsHandler,
                            java.util.Map<String, Object> attributes) {
                        // Real principal is set by WebSocketAuthChannelInterceptor on CONNECT.
                        return null;
                    }
                });
    }

    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        // Simple in-memory broker. Swap for RabbitMQ/ActiveMQ in production by switching to enableStompBrokerRelay.
        registry.enableSimpleBroker("/queue", "/topic");
        registry.setApplicationDestinationPrefixes("/app");
        registry.setUserDestinationPrefix("/user");
    }

    @Override
    public void configureClientInboundChannel(ChannelRegistration registration) {
        registration.interceptors(authInterceptor);
    }

    /** Use the user id as the destination resolver key (instead of Spring's default {@code Principal#getName()}). */
    public static String resolveUserDestinationKey(Authentication auth) {
        if (auth != null && auth.getPrincipal() instanceof AuthenticatedPrincipal p) {
            return p.userId().toString();
        }
        return null;
    }
}
