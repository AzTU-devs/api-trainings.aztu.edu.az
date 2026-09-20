package com.eduplatform.eduplatform_backend.common.security.oauth;

import com.eduplatform.eduplatform_backend.common.enums.AuthProvider;
import com.eduplatform.eduplatform_backend.common.error.AppException;
import com.eduplatform.eduplatform_backend.common.web.ApiError;
import com.eduplatform.eduplatform_backend.identity.oauth.OAuthAccountService;
import com.eduplatform.eduplatform_backend.identity.oauth.OAuthAccountService.ProviderProfile;
import com.eduplatform.eduplatform_backend.identity.web.dto.AuthTokens;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.security.web.authentication.SimpleUrlAuthenticationSuccessHandler;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * Translates a successful Spring Security OAuth2 login into:
 *   1. a {@link ProviderProfile} (Google/Facebook),
 *   2. an {@link OAuthAccountService#signIn sign-in} that resolves or creates the user and
 *      issues our platform JWT + refresh token.
 *
 * Returns JSON if the request came from an XHR/SPA, otherwise redirects to the public frontend
 * with the access token embedded in the URL fragment.
 */
@Component
public class OAuth2LoginSuccessHandler extends SimpleUrlAuthenticationSuccessHandler {

    private final OAuthAccountService accountService;
    private final ObjectMapper mapper;
    private final String publicFrontendUrl;

    public OAuth2LoginSuccessHandler(OAuthAccountService accountService,
                                     ObjectMapper mapper,
                                     @Value("${app.frontend.public-url}") String publicFrontendUrl) {
        this.accountService = accountService;
        this.mapper = mapper;
        this.publicFrontendUrl = publicFrontendUrl;
    }

    @Override
    public void onAuthenticationSuccess(HttpServletRequest req, HttpServletResponse res, Authentication authentication)
            throws IOException {
        OAuth2AuthenticationToken oauthToken = (OAuth2AuthenticationToken) authentication;
        String registrationId = oauthToken.getAuthorizedClientRegistrationId();
        OAuth2User oauthUser = oauthToken.getPrincipal();

        ProviderProfile profile = switch (registrationId) {
            case "google"   -> fromGoogle(oauthUser);
            case "facebook" -> fromFacebook(oauthUser);
            default -> throw new IllegalStateException("Unsupported OAuth provider: " + registrationId);
        };

        AuthTokens tokens;
        try {
            tokens = accountService.signIn(profile, req);
        } catch (AppException ex) {
            // This runs inside the security filter chain, where GlobalExceptionHandler never sees
            // it: an escaping AppException (e.g. 409 ACCOUNT_EXISTS) would reach the container as
            // a bare 500.
            res.setStatus(ex.status().value());
            res.setContentType(MediaType.APPLICATION_JSON_VALUE);
            mapper.writeValue(res.getOutputStream(),
                    ApiError.of(ex.status().value(), ex.code(), ex.getMessage(), req.getRequestURI()));
            return;
        }

        String accept = req.getHeader("Accept");
        if (accept != null && accept.contains(MediaType.APPLICATION_JSON_VALUE)) {
            res.setContentType(MediaType.APPLICATION_JSON_VALUE);
            mapper.writeValue(res.getOutputStream(), tokens);
        } else {
            String url = publicFrontendUrl + "/auth/callback"
                    + "?access_token="  + URLEncoder.encode(tokens.accessToken(),  StandardCharsets.UTF_8)
                    + "&refresh_token=" + URLEncoder.encode(tokens.refreshToken(), StandardCharsets.UTF_8);
            getRedirectStrategy().sendRedirect(req, res, url);
        }
    }

    private static ProviderProfile fromGoogle(OAuth2User u) {
        String sub  = u.getAttribute("sub");
        String email = u.getAttribute("email");
        String firstName = u.getAttribute("given_name");
        String lastName  = u.getAttribute("family_name");
        String name      = u.getAttribute("name");
        String picture   = u.getAttribute("picture");
        return new ProviderProfile(
                AuthProvider.GOOGLE, sub, email, assertsTrue(u.getAttribute("email_verified")), false,
                firstName, lastName, name, picture, new HashMap<>(u.getAttributes()));
    }

    private static ProviderProfile fromFacebook(OAuth2User u) {
        String id    = u.getAttribute("id");
        String email = u.getAttribute("email");           // may be null
        String name  = u.getAttribute("name");
        String first = null, last = null;
        if (name != null) {
            int sp = name.indexOf(' ');
            first = sp >= 0 ? name.substring(0, sp) : name;
            last  = sp >= 0 ? name.substring(sp + 1) : "";
        }
        String avatar = null;
        Object pic = u.getAttribute("picture");
        if (pic instanceof Map<?, ?> p && p.get("data") instanceof Map<?, ?> d) {
            Object url = d.get("url");
            if (url != null) avatar = url.toString();
        }
        // Facebook's Graph API makes no statement about whether the email is verified, and an
        // email being present is not one. Treating it as verified would let any Facebook account
        // that reports someone's address sign in as them through OAuthAccountService's auto-link.
        return new ProviderProfile(
                AuthProvider.FACEBOOK, id, email, false, false,
                first, last, name, avatar, new HashMap<>(u.getAttributes()));
    }

    /**
     * Whether a boolean claim is asserted true. Accepts the string form as well, which some
     * identity providers send, and treats a missing claim as not asserted.
     */
    private static boolean assertsTrue(Object claim) {
        return Boolean.TRUE.equals(claim) || (claim instanceof String str && Boolean.parseBoolean(str));
    }
}
