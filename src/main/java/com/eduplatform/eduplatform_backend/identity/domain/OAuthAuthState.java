package com.eduplatform.eduplatform_backend.identity.domain;

import com.eduplatform.eduplatform_backend.common.enums.AuthProvider;
import com.eduplatform.eduplatform_backend.common.enums.OAuthIntent;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.ColumnTransformer;

import java.time.Instant;
import java.util.UUID;

/**
 * Single-use, short-lived OAuth/OIDC flow state. Holds PKCE verifier + CSRF state + nonce.
 * Row is deleted on callback success or by a scheduled expiry sweep.
 */
@Entity
@Table(name = "oauth_auth_states")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class OAuthAuthState {

    @Id
    @Column(name = "state", nullable = false, updatable = false, length = 64)
    private String state;

    @Enumerated(EnumType.STRING)
    @Column(name = "provider", nullable = false, length = 20)
    private AuthProvider provider;

    @Column(name = "code_verifier", nullable = false, length = 128)
    private String codeVerifier;

    @Column(name = "nonce", length = 128)
    private String nonce;

    @Column(name = "redirect_uri", nullable = false, length = 512)
    private String redirectUri;

    @Enumerated(EnumType.STRING)
    @Column(name = "intent", nullable = false, length = 20)
    @Builder.Default
    private OAuthIntent intent = OAuthIntent.LOGIN;

    /** Populated only when {@link #intent} is LINK. */
    @Column(name = "link_user_id", columnDefinition = "uuid")
    private UUID linkUserId;

    @Column(name = "post_login_redirect", length = 512)
    private String postLoginRedirect;

    @ColumnTransformer(write = "?::inet")
    @Column(name = "ip_address", columnDefinition = "inet")
    private String ipAddress;

    @Column(name = "user_agent", length = 255)
    private String userAgent;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) createdAt = Instant.now();
    }
}
