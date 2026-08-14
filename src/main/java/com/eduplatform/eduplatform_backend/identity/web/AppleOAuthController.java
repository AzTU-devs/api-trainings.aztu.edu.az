package com.eduplatform.eduplatform_backend.identity.web;

import com.eduplatform.eduplatform_backend.common.web.ApiResponse;
import com.eduplatform.eduplatform_backend.identity.oauth.AppleSignInService;
import com.eduplatform.eduplatform_backend.identity.web.dto.AuthTokens;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * Sign in with Apple. Apple invokes the callback with a {@code form_post} (POST + form body),
 * so the callback is POST-only and tolerates either form data or JSON for cross-platform testing.
 */
@RestController
@RequestMapping("/api/auth/oauth/apple")
@Tag(name = "Auth", description = "Sign in with Apple")
public class AppleOAuthController {

    private final AppleSignInService apple;

    public AppleOAuthController(AppleSignInService apple) {
        this.apple = apple;
    }

    @GetMapping("/start")
    @Operation(summary = "Returns the Apple authorize URL the frontend should redirect to", security = {})
    public ApiResponse<StartResponse> start(HttpServletRequest req) {
        return ApiResponse.ok(new StartResponse(apple.startUrl(req)));
    }

    @PostMapping(value = "/callback",
            consumes = {MediaType.APPLICATION_FORM_URLENCODED_VALUE, MediaType.APPLICATION_JSON_VALUE})
    @Operation(summary = "Apple authorization-code callback", security = {})
    public void callback(@RequestParam String code,
                         @RequestParam String state,
                         HttpServletRequest req,
                         HttpServletResponse res) throws IOException {
        AuthTokens tokens = apple.handleCallback(code, state, req);
        // Apple's form_post lands here; redirect to the frontend with tokens in the URL.
        String url = apple.publicFrontendUrl() + "/auth/callback"
                + "?access_token="  + URLEncoder.encode(tokens.accessToken(),  StandardCharsets.UTF_8)
                + "&refresh_token=" + URLEncoder.encode(tokens.refreshToken(), StandardCharsets.UTF_8);
        res.sendRedirect(url);
    }

    public record StartResponse(String authorizeUrl) {}
}
