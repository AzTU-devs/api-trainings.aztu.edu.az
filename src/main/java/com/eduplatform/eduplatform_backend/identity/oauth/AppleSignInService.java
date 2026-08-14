package com.eduplatform.eduplatform_backend.identity.oauth;

import com.eduplatform.eduplatform_backend.common.enums.AuthProvider;
import com.eduplatform.eduplatform_backend.common.enums.OAuthIntent;
import com.eduplatform.eduplatform_backend.common.error.Errors;
import com.eduplatform.eduplatform_backend.common.security.TokenHasher;
import com.eduplatform.eduplatform_backend.common.security.config.OAuthProperties;
import com.eduplatform.eduplatform_backend.identity.domain.OAuthAuthState;
import com.eduplatform.eduplatform_backend.identity.domain.User;
import com.eduplatform.eduplatform_backend.identity.oauth.OAuthAccountService.ProviderProfile;
import com.eduplatform.eduplatform_backend.identity.repo.OAuthAuthStateRepository;
import com.eduplatform.eduplatform_backend.identity.service.AuthService;
import com.eduplatform.eduplatform_backend.identity.web.dto.AuthTokens;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/**
 * Sign in with Apple — custom OIDC flow that handles Apple's ES256 client_secret JWT,
 * JWKS-based id_token verification, and the one-time email/name returned on first auth.
 *
 * Endpoint shape (wired in Phase 7):
 *   GET /api/auth/oauth/apple/start    → {@link #startUrl(HttpServletRequest)}
 *   POST /api/auth/oauth/apple/callback → {@link #handleCallback(String, String, HttpServletRequest)}
 */
@Service
public class AppleSignInService {

    private static final String AUTHORIZE_URL = "https://appleid.apple.com/auth/authorize";
    private static final String TOKEN_URL     = "https://appleid.apple.com/auth/token";
    private static final String JWKS_URL      = "https://appleid.apple.com/auth/keys";
    private static final String ISSUER        = "https://appleid.apple.com";

    private final OAuthProperties.Apple cfg;
    private final AppleClientSecretService secretService;
    private final OAuthAuthStateRepository stateRepo;
    private final OAuthAccountService accountService;
    private final AuthService authService;
    private final String publicFrontendUrl;
    private final RestClient http = RestClient.create();
    private final JwtDecoder appleJwtDecoder;

    public AppleSignInService(OAuthProperties props,
                              AppleClientSecretService secretService,
                              OAuthAuthStateRepository stateRepo,
                              OAuthAccountService accountService,
                              AuthService authService,
                              @Value("${app.frontend.public-url}") String publicFrontendUrl) {
        this.cfg = props.apple();
        this.secretService = secretService;
        this.stateRepo = stateRepo;
        this.accountService = accountService;
        this.authService = authService;
        this.publicFrontendUrl = publicFrontendUrl;
        this.appleJwtDecoder = NimbusJwtDecoder.withJwkSetUri(JWKS_URL).build();
    }

    /** Build the authorize URL and persist short-lived PKCE/state for the callback step. */
    @Transactional
    public String startUrl(HttpServletRequest req) {
        requireConfigured();
        String state = TokenHasher.randomToken(32);
        String nonce = TokenHasher.randomToken(16);
        // Apple does not require PKCE; we still record a value so the schema stays uniform.
        String pkceVerifier = TokenHasher.randomToken(32);

        OAuthAuthState row = OAuthAuthState.builder()
                .state(state)
                .provider(AuthProvider.APPLE)
                .codeVerifier(pkceVerifier)
                .nonce(nonce)
                .redirectUri(cfg.redirectUri())
                .intent(OAuthIntent.LOGIN)
                .ipAddress(req.getRemoteAddr())
                .userAgent(truncate(req.getHeader("User-Agent"), 255))
                .createdAt(Instant.now())
                .expiresAt(Instant.now().plus(Duration.ofMinutes(10)))
                .build();
        stateRepo.save(row);

        return AUTHORIZE_URL
                + "?response_type=code%20id_token"
                + "&response_mode=form_post"
                + "&client_id=" + URLEncoder.encode(cfg.servicesId(), StandardCharsets.UTF_8)
                + "&redirect_uri=" + URLEncoder.encode(cfg.redirectUri(), StandardCharsets.UTF_8)
                + "&scope=" + URLEncoder.encode("name email", StandardCharsets.UTF_8)
                + "&state=" + state
                + "&nonce=" + nonce;
    }

    /**
     * Exchange the auth code for tokens, verify the id_token via JWKS, then issue our own JWTs.
     * Apple posts {@code code}, {@code id_token}, {@code state}, and (only on first sign-in)
     * a {@code user} JSON blob containing the user's name. We accept the latter optionally.
     */
    @Transactional
    public AuthTokens handleCallback(String code, String state, HttpServletRequest req) {
        requireConfigured();
        OAuthAuthState row = stateRepo.findById(state)
                .orElseThrow(() -> Errors.badRequest("INVALID_OAUTH_STATE", "Unknown OAuth state"));
        if (row.getProvider() != AuthProvider.APPLE) {
            throw Errors.badRequest("INVALID_OAUTH_STATE", "State does not belong to Apple");
        }
        if (Instant.now().isAfter(row.getExpiresAt())) {
            stateRepo.delete(row);
            throw Errors.badRequest("OAUTH_STATE_EXPIRED", "OAuth state expired; please restart sign-in");
        }
        stateRepo.delete(row); // single-use

        Map<String, Object> tokenResponse = exchangeCode(code);
        String idToken = (String) tokenResponse.get("id_token");
        if (idToken == null) {
            throw Errors.unauthorized("APPLE_TOKEN_MISSING_ID_TOKEN", "Apple did not return id_token");
        }

        Jwt jwt = decodeAndVerify(idToken, row.getNonce());
        ProviderProfile profile = toProfile(jwt);
        User user = accountService.resolveOrCreate(profile);
        return authService.issueTokens(user, req);
    }

    public String publicFrontendUrl() { return publicFrontendUrl; }

    private Map<String, Object> exchangeCode(String code) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "authorization_code");
        form.add("code", code);
        form.add("redirect_uri", cfg.redirectUri());
        form.add("client_id", cfg.servicesId());
        form.add("client_secret", secretService.currentSecret());

        try {
            return http.post()
                    .uri(TOKEN_URL)
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(form)
                    .retrieve()
                    .body(new ParameterizedTypeReference<Map<String, Object>>() {});
        } catch (Exception ex) {
            throw Errors.unauthorized("APPLE_TOKEN_EXCHANGE_FAILED", "Apple token exchange failed: " + ex.getMessage());
        }
    }

    private Jwt decodeAndVerify(String idToken, String expectedNonce) {
        Jwt jwt;
        try {
            jwt = appleJwtDecoder.decode(idToken);
        } catch (Exception ex) {
            throw Errors.unauthorized("APPLE_INVALID_ID_TOKEN", "Apple id_token failed signature verification");
        }
        if (!ISSUER.equals(jwt.getIssuer().toString())) {
            throw Errors.unauthorized("APPLE_INVALID_ISSUER", "Apple id_token issuer mismatch");
        }
        if (!jwt.getAudience().contains(cfg.servicesId())) {
            throw Errors.unauthorized("APPLE_INVALID_AUDIENCE", "Apple id_token audience mismatch");
        }
        String nonce = jwt.getClaim("nonce");
        if (expectedNonce != null && !expectedNonce.equals(nonce)) {
            throw Errors.unauthorized("APPLE_INVALID_NONCE", "Apple id_token nonce mismatch");
        }
        return jwt;
    }

    private static ProviderProfile toProfile(Jwt jwt) {
        String sub = jwt.getSubject();
        String email = jwt.getClaim("email");
        Boolean verified = jwt.getClaim("email_verified");
        Boolean privateEmail = jwt.getClaim("is_private_email");

        return new ProviderProfile(
                AuthProvider.APPLE, sub, email,
                Boolean.TRUE.equals(verified),
                Boolean.TRUE.equals(privateEmail),
                null, null, null, null,
                new HashMap<>(jwt.getClaims()));
    }

    private void requireConfigured() {
        if (cfg.servicesId() == null || cfg.servicesId().isBlank()
                || cfg.teamId() == null || cfg.teamId().isBlank()
                || cfg.keyId() == null || cfg.keyId().isBlank()) {
            throw Errors.unprocessable("APPLE_NOT_CONFIGURED",
                    "Sign in with Apple is not configured on this server");
        }
    }

    private static String truncate(String s, int max) {
        return s == null ? null : (s.length() <= max ? s : s.substring(0, max));
    }
}
