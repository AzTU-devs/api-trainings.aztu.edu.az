package com.eduplatform.eduplatform_backend.audit.service;

import jakarta.servlet.http.HttpServletRequest;

/** Small helpers for pulling client metadata off an HTTP request. */
public final class HttpMeta {

    public static final String REQUEST_ID_ATTR = "ep.requestId";

    /**
     * Whether to trust client-supplied X-Forwarded-For / X-Real-IP headers. Only enable this
     * ({@code app.security.trust-forward-headers=true}) when the app sits behind a trusted proxy
     * that sets these headers — otherwise clients can spoof their source IP (defeating the IP
     * blocklist and forging audit/security records). Configured once at startup.
     */
    private static volatile boolean trustForwardHeaders = false;

    private HttpMeta() {}

    public static void setTrustForwardHeaders(boolean value) {
        trustForwardHeaders = value;
    }

    /** Client IP. Honors X-Forwarded-For/X-Real-IP only when forward headers are trusted; else the socket address. */
    public static String clientIp(HttpServletRequest request) {
        if (request == null) return null;
        if (trustForwardHeaders) {
            String xff = request.getHeader("X-Forwarded-For");
            if (xff != null && !xff.isBlank()) {
                int comma = xff.indexOf(',');
                return (comma > 0 ? xff.substring(0, comma) : xff).trim();
            }
            String real = request.getHeader("X-Real-IP");
            if (real != null && !real.isBlank()) return real.trim();
        }
        return request.getRemoteAddr();
    }

    public static String userAgent(HttpServletRequest request) {
        if (request == null) return null;
        String ua = request.getHeader("User-Agent");
        if (ua == null) return null;
        return ua.length() > 255 ? ua.substring(0, 255) : ua;
    }
}
