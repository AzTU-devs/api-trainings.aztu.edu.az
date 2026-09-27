package com.eduplatform.eduplatform_backend.audit.service;

import jakarta.servlet.http.HttpServletRequest;

/** Small helpers for pulling client metadata off an HTTP request. */
public final class HttpMeta {

    public static final String REQUEST_ID_ATTR = "ep.requestId";

    private HttpMeta() {}

    /**
     * Client IP, exactly as Tomcat's RemoteIpValve resolved it
     * ({@code server.forward-headers-strategy=native} in application.properties).
     *
     * <p>Deliberately parses no header itself. The valve consults X-Forwarded-For only when the
     * connection comes from a trusted internal proxy, and then reads it right to left, stopping at
     * the first hop that is not one. This method used to take the leftmost entry instead, and the
     * API vhost's {@code $proxy_add_x_forwarded_for} appends to whatever the client sent, so the
     * leftmost entry was the client's own choice. The auth rate limiter, the IP blocklist and audit
     * attribution all key on this value.
     *
     * <p>Null unless it is an IP literal. The valve takes a forwarded entry as whatever text it
     * holds; ClientAddressConfig puts the TCP peer back when that text is no address, and this
     * check keeps anything that got past it out of the INET columns the value is stored in, where
     * a malformed one failed the whole request.
     */
    public static String clientIp(HttpServletRequest request) {
        if (request == null) return null;
        String address = request.getRemoteAddr();
        return BlockedIpService.canonical(address).isPresent() ? address : null;
    }

    public static String userAgent(HttpServletRequest request) {
        if (request == null) return null;
        String ua = request.getHeader("User-Agent");
        if (ua == null) return null;
        return ua.length() > 255 ? ua.substring(0, 255) : ua;
    }
}
