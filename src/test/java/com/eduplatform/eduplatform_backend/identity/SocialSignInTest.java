package com.eduplatform.eduplatform_backend.identity;

import com.eduplatform.eduplatform_backend.common.enums.AuthProvider;
import com.eduplatform.eduplatform_backend.common.error.AppException;
import com.eduplatform.eduplatform_backend.common.security.oauth.OAuth2LoginSuccessHandler;
import com.eduplatform.eduplatform_backend.identity.oauth.OAuthAccountService;
import com.eduplatform.eduplatform_backend.identity.oauth.OAuthAccountService.ProviderProfile;
import com.eduplatform.eduplatform_backend.identity.web.dto.AuthTokens;
import com.eduplatform.eduplatform_backend.support.AbstractIntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;
import org.springframework.security.oauth2.core.user.OAuth2User;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import static com.eduplatform.eduplatform_backend.support.Json.texts;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Social sign-in below the provider redirect, which needs a live Google, Facebook or Apple and is
 * not configured in this context. The account rules are tested on OAuthAccountService itself; the
 * provider mapping and the returning-user path are tested by handing OAuth2LoginSuccessHandler the
 * authentication Spring Security builds after a successful Google or Facebook login.
 *
 * <p>An existing account is linked by email only when the provider itself asserts that the email
 * is verified (Google's and Apple's email_verified claim; Facebook asserts nothing), and never
 * when the account is an ADMIN or SUPER_ADMIN. Otherwise the caller gets 409 ACCOUNT_EXISTS and
 * signs in with the password. Linking signs the caller in as that account with no password at
 * all, so a Facebook account reporting an admin's address used to be handed an admin session.
 */
class SocialSignInTest extends AbstractIntegrationTest {

    @Autowired
    private OAuthAccountService accounts;

    @Autowired
    private OAuth2LoginSuccessHandler successHandler;

    @Autowired
    private ObjectMapper objectMapper;

    // ── linking rules, on the service ───────────────────────────────────

    @Test
    void aVerifiedGoogleEmailIsLinkedToTheOrdinaryAccountThatHoldsIt() {
        TestUser existing = newUser("linked", "USER");
        ProviderProfile google = profile(AuthProvider.GOOGLE, existing.email(), true);

        AuthTokens tokens = accounts.signIn(google, new MockHttpServletRequest());

        assertThat(tokens.user().id()).isEqualTo(existing.id());
        assertThat(identityOwner(google)).isEqualTo(existing.id());
        assertThat(accountsWithEmail(existing.email())).isEqualTo(1);
    }

    /**
     * The profile as the Facebook mapping builds it: Facebook says nothing about verification, so
     * the email it reports proves nothing about who owns the inbox. The mapping itself is covered
     * by {@link #aFacebookLoginReportingAnExistingAccountsEmailIsRefusedThroughTheHandler}.
     */
    @Test
    void aFacebookProfileWhoseEmailMatchesAnAccountIsRefusedAndNotLinked() {
        TestUser existing = newUser("facebooked", "USER");
        ProviderProfile facebook = profile(AuthProvider.FACEBOOK, existing.email(), false);

        assertAccountExists(facebook);

        assertNotLinked(facebook, existing);
    }

    @Test
    void anUnverifiedGoogleOrAppleEmailIsRefusedAndNotLinked() {
        TestUser existing = newUser("unverified", "USER");

        for (AuthProvider provider : List.of(AuthProvider.GOOGLE, AuthProvider.APPLE)) {
            ProviderProfile unverified = profile(provider, existing.email(), false);

            assertAccountExists(unverified);

            assertNotLinked(unverified, existing);
        }
    }

    /**
     * Every provider is refused, each claiming a verified email, Facebook included, so the rule is
     * seen to rest on the account's role and not on the claim. The email is sent in upper case as
     * well, since the lookup ignores case and a provider may report it either way.
     */
    @Test
    void noProviderIsLinkedToAnAdminOrSuperAdminAccount() {
        for (String role : List.of("ADMIN", "SUPER_ADMIN")) {
            TestUser admin = newUser(role.toLowerCase(Locale.ROOT).replace('_', '-'), role);

            for (AuthProvider provider : List.of(AuthProvider.GOOGLE, AuthProvider.APPLE, AuthProvider.FACEBOOK)) {
                for (String email : List.of(admin.email(), admin.email().toUpperCase(Locale.ROOT))) {
                    ProviderProfile claimingVerified = profile(provider, email, true);

                    assertAccountExists(claimingVerified);

                    assertNotLinked(claimingVerified, admin);
                }
            }
            assertThat(rolesInDb(admin.id())).containsExactly(role);
        }
    }

    /** The refusals are about taken emails only; a Facebook user with a new email still gets an account. */
    @Test
    void aFacebookUserWithAnUnusedEmailStillGetsANewAccount() {
        String email = uniqueEmail("newcomer");
        ProviderProfile facebook = profile(AuthProvider.FACEBOOK, email, false);

        AuthTokens tokens = accounts.signIn(facebook, new MockHttpServletRequest());

        UUID created = tokens.user().id();
        assertThat(identityOwner(facebook)).isEqualTo(created);
        assertThat(accountsWithEmail(email)).isEqualTo(1);
        assertThat(rolesInDb(created)).containsExactly("USER");
    }

    // ── through the login success handler ────────────────────────────────

    /** The Facebook mapping used to mark any email it received as verified, which is what opened the link. */
    @Test
    void aFacebookLoginReportingAnExistingAccountsEmailIsRefusedThroughTheHandler() throws Exception {
        TestUser existing = newUser("facebook-handler", "USER");
        String facebookId = "fb-" + word();

        MockHttpServletResponse response = completeLogin("facebook", "id",
                Map.of("id", facebookId, "email", existing.email(), "name", "Someone Else"));

        assertThat(response.getStatus()).as("status, body: %s", response.getContentAsString()).isEqualTo(409);
        assertThat(objectMapper.readTree(response.getContentAsByteArray()).path("code").asText())
                .isEqualTo("ACCOUNT_EXISTS");
        assertThat(identitiesOf(existing.id())).as("identities linked to the account").isZero();
        assertThat(jdbc.queryForObject("select count(*) from user_identities where provider_user_id = ?",
                Integer.class, facebookId)).as("identities for the Facebook account").isZero();
    }

    /**
     * For Google, the email_verified claim is the only thing that vouches for the address, so the
     * mapping must carry it through as sent: an email Google has not verified, or one reported with
     * no claim at all, must not open the account that holds it.
     */
    @Test
    void aGoogleLoginWhoseEmailGoogleDoesNotVerifyIsRefusedThroughTheHandler() throws Exception {
        TestUser existing = newUser("google-unverified", "USER");

        for (Map<String, Object> attributes : List.of(
                Map.<String, Object>of("sub", "google-" + word(), "email", existing.email(), "email_verified", false),
                Map.<String, Object>of("sub", "google-" + word(), "email", existing.email(), "email_verified", "false"),
                Map.<String, Object>of("sub", "google-" + word(), "email", existing.email()))) {
            MockHttpServletResponse response = completeLogin("google", "sub", attributes);

            assertThat(response.getStatus()).as("status for %s, body: %s", attributes, response.getContentAsString())
                    .isEqualTo(409);
            assertThat(objectMapper.readTree(response.getContentAsByteArray()).path("code").asText())
                    .isEqualTo("ACCOUNT_EXISTS");
        }
        assertThat(identitiesOf(existing.id())).as("identities linked to the account").isZero();
        assertThat(accountsWithEmail(existing.email())).as("accounts with %s", existing.email()).isEqualTo(1);
    }

    /**
     * Issuing tokens reads the user's roles, which are lazy. Resolving used to commit and close its
     * session first, so a returning user (a proxy from the linked identity) failed with a
     * LazyInitializationException; only a brand-new account, built in that same session, worked.
     */
    @Test
    void aReturningGoogleUserIsSignedInAgain() throws Exception {
        assertSignedInAgain("google", "sub", Map.of("sub", "google-" + word(),
                "email", uniqueEmail("google-returning"), "email_verified", true,
                "given_name", "Returning", "family_name", "User"));
    }

    @Test
    void aReturningFacebookUserIsSignedInAgain() throws Exception {
        assertSignedInAgain("facebook", "id", Map.of("id", "fb-" + word(),
                "email", uniqueEmail("facebook-returning"), "name", "Returning User"));
    }

    /** Linking loads the account by email, and its roles are just as lazy as a returning user's. */
    @Test
    void aGoogleLoginLinkingAnExistingAccountIsSignedInAsThatAccount() throws Exception {
        TestUser existing = newUser("google-link", "USER");

        JsonNode tokens = tokensFrom(completeLogin("google", "sub",
                Map.of("sub", "google-" + word(), "email", existing.email(), "email_verified", true)));

        assertThat(tokens.path("user").path("id").asText()).isEqualTo(existing.id().toString());
        assertThat(texts(tokens.path("user").path("roles"))).containsExactly("USER");
        assertThat(identitiesOf(existing.id())).as("identities linked to the account").isEqualTo(1);
    }

    // ── helpers ─────────────────────────────────────────────────────────

    private static ProviderProfile profile(AuthProvider provider, String email, boolean emailVerified) {
        return new ProviderProfile(provider, provider.name().toLowerCase(Locale.ROOT) + "-" + word(), email,
                emailVerified, false, "Social", "User", "Social User", null,
                new HashMap<>(Map.of("email", email)));
    }

    private void assertAccountExists(ProviderProfile profile) {
        assertThatThrownBy(() -> accounts.signIn(profile, new MockHttpServletRequest()))
                .as("%s sign-in with %s (verified: %s)", profile.provider(), profile.email(), profile.emailVerified())
                .isInstanceOfSatisfying(AppException.class, refused -> {
                    assertThat(refused.status()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(refused.code()).isEqualTo("ACCOUNT_EXISTS");
                });
    }

    private void assertNotLinked(ProviderProfile refused, TestUser existing) {
        assertThat(identityOwner(refused)).as("owner of the refused %s identity", refused.provider()).isNull();
        assertThat(identitiesOf(existing.id())).as("identities linked to %s", existing.email()).isZero();
        assertThat(accountsWithEmail(existing.email())).as("accounts with %s", existing.email()).isEqualTo(1);
    }

    /** The account the provider identity is linked to, or null if it is linked to none. */
    private UUID identityOwner(ProviderProfile profile) {
        return jdbc.queryForList("select user_id from user_identities where provider = ? and provider_user_id = ?",
                        UUID.class, profile.provider().name(), profile.providerUserId())
                .stream().findFirst().orElse(null);
    }

    private int identitiesOf(UUID userId) {
        return jdbc.queryForObject("select count(*) from user_identities where user_id = ?", Integer.class, userId);
    }

    private int accountsWithEmail(String email) {
        return jdbc.queryForObject("select count(*) from users where lower(email) = lower(?)", Integer.class, email);
    }

    /** Signs in twice with the same provider account; the second time resolves the identity linked by the first. */
    private void assertSignedInAgain(String registrationId, String nameAttribute, Map<String, Object> attributes)
            throws Exception {
        JsonNode first = tokensFrom(completeLogin(registrationId, nameAttribute, attributes));

        JsonNode again = tokensFrom(completeLogin(registrationId, nameAttribute, attributes));

        assertThat(again.path("accessToken").asText()).as("access token").isNotBlank();
        assertThat(again.path("refreshToken").asText()).as("refresh token").isNotBlank();
        assertThat(again.path("user").path("id").asText()).as("account signed in")
                .isEqualTo(first.path("user").path("id").asText());
        assertThat(texts(again.path("user").path("roles"))).containsExactly("USER");
    }

    /**
     * Hands the success handler what Spring Security's OAuth2 login filter hands it: the provider's
     * user attributes, under the client registration id. Asks for JSON, as the SPA does, so the
     * tokens come back in the body rather than in a redirect.
     */
    private MockHttpServletResponse completeLogin(String registrationId, String nameAttribute,
                                                  Map<String, Object> attributes) throws Exception {
        OAuth2User user = new DefaultOAuth2User(List.of(new SimpleGrantedAuthority("OAUTH2_USER")),
                attributes, nameAttribute);
        OAuth2AuthenticationToken authentication =
                new OAuth2AuthenticationToken(user, user.getAuthorities(), registrationId);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/login/oauth2/code/" + registrationId);
        request.addHeader("Accept", "application/json");
        MockHttpServletResponse response = new MockHttpServletResponse();

        successHandler.onAuthenticationSuccess(request, response, authentication);

        return response;
    }

    private JsonNode tokensFrom(MockHttpServletResponse response) throws Exception {
        assertThat(response.getStatus()).as("status, body: %s", response.getContentAsString()).isEqualTo(200);
        return objectMapper.readTree(response.getContentAsByteArray());
    }
}
