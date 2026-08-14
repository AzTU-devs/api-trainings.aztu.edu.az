package com.eduplatform.eduplatform_backend.common.config;

import com.eduplatform.eduplatform_backend.audit.service.HttpMeta;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

/** Wires the {@code app.security.trust-forward-headers} flag into {@link HttpMeta} at startup. */
@Configuration
public class ForwardedHeaderConfig {

    public ForwardedHeaderConfig(@Value("${app.security.trust-forward-headers:false}") boolean trust) {
        HttpMeta.setTrustForwardHeaders(trust);
    }
}
