package com.eduplatform.eduplatform_backend.common.config;

import com.eduplatform.eduplatform_backend.audit.service.BlockedIpService;
import jakarta.servlet.ServletException;
import org.apache.catalina.connector.Request;
import org.apache.catalina.connector.Response;
import org.apache.catalina.valves.ValveBase;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.web.embedded.tomcat.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

import java.io.IOException;

/**
 * Makes sure the client address every request carries is an IP address.
 *
 * <p>Tomcat's RemoteIpValve (see {@code server.forward-headers-strategy} in application.properties)
 * takes the client address from X-Forwarded-For as the text it finds there. It never checks that
 * the text is an address, so a malformed entry — "10.63.0.1743", "2001:db8::zz" — became the
 * request's remote address. Everything downstream trusts that value: the audit log, the refresh
 * tokens and the security events store it in INET columns, and the insert failed, which rolled
 * back sign-in, refresh, logout and every audited admin write with a 400. Whoever can put such an
 * entry where the valve reads it (a client on a network the valve trusts as a proxy) could break
 * those for themselves at will; a misbehaving proxy, for everyone behind it.
 *
 * <p>The valve here runs right after RemoteIpValve and puts back the address of the TCP peer —
 * what the request would have carried with no forwarded header at all — whenever the resolved
 * one is not an IP literal. HttpMeta.clientIp checks again for anything reached another way.
 */
@Configuration
public class ClientAddressConfig {

    @Bean
    WebServerFactoryCustomizer<TomcatServletWebServerFactory> clientAddressValveCustomizer() {
        return new ValveCustomizer();
    }

    /**
     * Ordered last, after Spring Boot's own TomcatWebServerFactoryCustomizer (order 0) has added
     * RemoteIpValve: engine valves run in the order they were added, and this one has to see the
     * address RemoteIpValve resolved.
     */
    static final class ValveCustomizer implements WebServerFactoryCustomizer<TomcatServletWebServerFactory>, Ordered {

        @Override
        public void customize(TomcatServletWebServerFactory factory) {
            factory.addEngineValves(new ClientAddressValve());
        }

        @Override
        public int getOrder() {
            return Ordered.LOWEST_PRECEDENCE;
        }
    }

    static final class ClientAddressValve extends ValveBase {

        private static final Logger log = LoggerFactory.getLogger(ClientAddressValve.class);

        ClientAddressValve() {
            super(true);   // async requests (the streaming uploads, the WebSocket handshake) pass through too
        }

        @Override
        public void invoke(Request request, Response response) throws IOException, ServletException {
            String resolved = request.getRemoteAddr();
            if (BlockedIpService.canonical(resolved).isEmpty()) {
                String peer = request.getPeerAddr();
                // DEBUG: the header's content is the sender's choice, and a WARN per request would
                // hand them a way to fill the log.
                log.debug("Forwarded client address {} is not an IP address; using the peer {}", resolved, peer);
                request.setRemoteAddr(peer);
                request.setRemoteHost(peer);
            }
            getNext().invoke(request, response);
        }
    }
}
