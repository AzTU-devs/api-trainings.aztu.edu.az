package com.eduplatform.eduplatform_backend.notification.ws;

import com.eduplatform.eduplatform_backend.common.security.AuthenticatedPrincipal;
import com.eduplatform.eduplatform_backend.common.security.JwtService;
import io.jsonwebtoken.Claims;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Authenticates STOMP CONNECT frames using the same JWT scheme as REST.
 * The client must send {@code Authorization: Bearer <jwt>} as a STOMP header on connect.
 * The principal becomes the connection's {@code user}, which lets us route
 * {@code /user/queue/...} messages to that specific session.
 */
@Component
public class WebSocketAuthChannelInterceptor implements ChannelInterceptor {

    private final JwtService jwt;

    public WebSocketAuthChannelInterceptor(JwtService jwt) {
        this.jwt = jwt;
    }

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor accessor = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
        if (accessor == null || !StompCommand.CONNECT.equals(accessor.getCommand())) {
            return message;
        }

        String header = accessor.getFirstNativeHeader("Authorization");
        if (header == null || !header.startsWith("Bearer ")) {
            throw new IllegalArgumentException("STOMP CONNECT missing Bearer token");
        }
        Claims claims = jwt.parse(header.substring("Bearer ".length()).trim());

        UUID userId = UUID.fromString(claims.getSubject());
        String email = claims.get("email", String.class);
        List<String> roles = JwtService.stringList(claims, "roles");
        List<String> perms = JwtService.stringList(claims, "perms");

        Set<String> authorities = new HashSet<>();
        roles.forEach(r -> authorities.add("ROLE_" + r));
        authorities.addAll(perms);

        AuthenticatedPrincipal principal = new AuthenticatedPrincipal(
                userId, email, Set.copyOf(roles), Set.copyOf(perms));
        var auth = new UsernamePasswordAuthenticationToken(
                principal, null,
                authorities.stream().map(SimpleGrantedAuthority::new).toList());

        accessor.setUser(StompPrincipal.of(auth));
        return message;
    }
}
