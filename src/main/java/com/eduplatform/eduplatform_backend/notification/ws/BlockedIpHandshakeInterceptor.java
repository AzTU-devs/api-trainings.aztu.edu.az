package com.eduplatform.eduplatform_backend.notification.ws;

import com.eduplatform.eduplatform_backend.audit.service.BlockedIpService;
import com.eduplatform.eduplatform_backend.audit.service.HttpMeta;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;

import java.net.InetSocketAddress;
import java.util.Map;

/**
 * Rejects the WebSocket handshake from blocked IP addresses.
 *
 * <p>The address is the one Tomcat's RemoteIpValve resolved
 * ({@code server.forward-headers-strategy=native}). It is read through {@link HttpMeta#clientIp}
 * so that it is the same string the HTTP blocklist check and the admin's block list use.
 * {@code ServerHttpRequest.getRemoteAddress()} would re-render a forwarded IPv6 address in Java's
 * uncompressed form, which never equals the compressed form that was blocked.
 */
@Component
public class BlockedIpHandshakeInterceptor implements HandshakeInterceptor {

    private final BlockedIpService blockedIps;

    public BlockedIpHandshakeInterceptor(BlockedIpService blockedIps) {
        this.blockedIps = blockedIps;
    }

    @Override
    public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                   WebSocketHandler wsHandler, Map<String, Object> attributes) {
        if (blockedIps.isBlocked(clientIp(request))) {
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

    private static String clientIp(ServerHttpRequest request) {
        if (request instanceof ServletServerHttpRequest servlet) {
            return HttpMeta.clientIp(servlet.getServletRequest());
        }
        InetSocketAddress remote = request.getRemoteAddress();
        return remote == null || remote.getAddress() == null ? null : remote.getAddress().getHostAddress();
    }
}
