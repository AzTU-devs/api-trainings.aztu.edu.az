package com.eduplatform.eduplatform_backend.notification.ws;

import com.eduplatform.eduplatform_backend.audit.service.BlockedIpService;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;

import java.util.Map;

/** Rejects the WebSocket handshake from blocked IP addresses (socket address, not forwarded headers). */
@Component
public class BlockedIpHandshakeInterceptor implements HandshakeInterceptor {

    private final BlockedIpService blockedIps;

    public BlockedIpHandshakeInterceptor(BlockedIpService blockedIps) {
        this.blockedIps = blockedIps;
    }

    @Override
    public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                   WebSocketHandler wsHandler, Map<String, Object> attributes) {
        String ip = request.getRemoteAddress() == null ? null
                : request.getRemoteAddress().getAddress().getHostAddress();
        if (blockedIps.isBlocked(ip)) {
            response.setStatusCode(HttpStatus.FORBIDDEN);
            return false;
        }
        return true;
    }

    @Override
    public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response,
                               WebSocketHandler wsHandler, Exception exception) {
        // no-op
    }
}
