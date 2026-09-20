package com.eduplatform.eduplatform_backend.identity.oauth;

import com.eduplatform.eduplatform_backend.common.enums.AuthProvider;
import com.eduplatform.eduplatform_backend.common.enums.RoleCode;
import com.eduplatform.eduplatform_backend.common.enums.UserStatus;
import com.eduplatform.eduplatform_backend.common.error.Errors;
import com.eduplatform.eduplatform_backend.identity.domain.Role;
import com.eduplatform.eduplatform_backend.identity.domain.User;
import com.eduplatform.eduplatform_backend.identity.domain.UserIdentity;
import com.eduplatform.eduplatform_backend.identity.domain.UserRole;
import com.eduplatform.eduplatform_backend.identity.domain.UserRoleId;
import com.eduplatform.eduplatform_backend.identity.repo.RoleRepository;
import com.eduplatform.eduplatform_backend.identity.repo.UserIdentityRepository;
import com.eduplatform.eduplatform_backend.identity.repo.UserRepository;
import com.eduplatform.eduplatform_backend.identity.repo.UserRoleRepository;
import com.eduplatform.eduplatform_backend.identity.service.AuthService;
import com.eduplatform.eduplatform_backend.identity.web.dto.AuthTokens;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.EnumSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Resolve-or-create a {@link User} given a provider's profile.
 *
 * Rules (mirror docs/architecture/01-erd-and-schema.md §12):
 *   1. Provider identity already linked → log that user in.
 *   2. Provider identity not linked and a user with this email exists → auto-link (create a new
 *      {@link UserIdentity} pointing at that user), but only if the provider asserts the email is
 *      verified and the account is not an admin; otherwise 409 ACCOUNT_EXISTS.
 *   3. No user with this email → create a new user with {@code password_hash = NULL} and link the
 *      identity.
 *
 * Auto-linking signs the caller in as whoever owns the email here, with no password, so it is
 * only as safe as the provider's word that the caller owns that inbox.
 */
@Service
public class OAuthAccountService {

    /**
     * Accounts that are never reachable through an email match. A provider's email check is
     * weaker than the admin's own password, and one wrong assertion would hand out an admin session.
     */
    private static final Set<RoleCode> NEVER_AUTO_LINKED = EnumSet.of(RoleCode.ADMIN, RoleCode.SUPER_ADMIN);

    private final UserRepository users;
    private final UserIdentityRepository identities;
    private final RoleRepository roles;
    private final UserRoleRepository userRoles;
    private final AuthService authService;

    public OAuthAccountService(UserRepository users, UserIdentityRepository identities, RoleRepository roles,
                               UserRoleRepository userRoles, AuthService authService) {
        this.users = users;
        this.identities = identities;
        this.roles = roles;
        this.userRoles = userRoles;
        this.authService = authService;
    }

    /**
     * Resolves the provider identity to a user and issues our tokens, in one transaction. Issuing
     * reads the user's roles, which are lazy: a returning user comes back as a proxy (rule 1) or
     * with an unloaded role collection (rule 2), and with open-in-view off, reading them after
     * the resolving transaction has closed fails with LazyInitializationException.
     */
    @Transactional
    public AuthTokens signIn(ProviderProfile profile, HttpServletRequest req) {
        User user = resolveOrCreate(profile);
        // The same gate password login applies. Without it a suspended or locked account could
        // still get tokens by signing in with the social provider linked to it, which is a way
        // around every reason an account gets disabled.
        if (user.getStatus() != UserStatus.ACTIVE) {
            throw Errors.forbidden("ACCOUNT_NOT_ACTIVE", "Account is " + user.getStatus());
        }
        return authService.issueTokens(user, req);
    }

    @Transactional
    public User resolveOrCreate(ProviderProfile profile) {
        // Rule 1
        Optional<UserIdentity> existing = identities.findByProviderAndProviderUserId(
                profile.provider(), profile.providerUserId());
        if (existing.isPresent()) {
            UserIdentity id = existing.get();
            id.setLastLoginAt(Instant.now());
            id.setDisplayName(profile.displayName());
            id.setAvatarUrl(profile.avatarUrl());
            id.setRawProfile(profile.rawProfile());
            identities.save(id);
            return id.getUser();
        }

        // Rule 2: the email is taken. Link to it only when that is safe; otherwise tell the caller
        // to use that account, since rule 3 cannot create a second one with the same email.
        if (profile.email() != null) {
            Optional<User> matched = users.findByEmailIgnoreCase(profile.email());
            if (matched.isPresent()) {
                User user = matched.get();
                if (!mayAutoLink(profile, user)) {
                    throw Errors.conflict("ACCOUNT_EXISTS",
                            "An account with this email already exists. Sign in with its password instead.");
                }
                identities.save(buildIdentity(profile, user));
                return user;
            }
        }

        // Rule 3: brand-new user. password_hash stays NULL.
        User user = User.builder()
                .email(profile.email() == null
                        ? syntheticEmail(profile)
                        : profile.email())
                .firstName(orDefault(profile.firstName(), "User"))
                .lastName(orDefault(profile.lastName(), ""))
                .status(UserStatus.ACTIVE)
                .locale("en")
                .emailVerifiedAt(profile.emailVerified() ? Instant.now() : null)
                .build();
        user.setId(UUID.randomUUID());

        // The link goes on BEFORE save(): the hand-assigned id makes save() a merge that returns
        // a managed copy, and a link added to the original afterwards is never written, which
        // left every social signup with no role. cascade=ALL on User.userRoles carries it over.
        Role userRole = roles.findByCode(RoleCode.USER)
                .orElseThrow(() -> new IllegalStateException("Role USER missing"));
        user.getUserRoles().add(UserRole.builder()
                .id(new UserRoleId(user.getId(), userRole.getId()))
                .user(user)
                .role(userRole)
                .grantedAt(Instant.now())
                .build());
        user = users.save(user);

        identities.save(buildIdentity(profile, user));
        return user;
    }

    private boolean mayAutoLink(ProviderProfile profile, User user) {
        if (!profile.emailVerified()) {
            return false;
        }
        if (userRoles.findRoleCodesByUserId(user.getId()).stream().anyMatch(NEVER_AUTO_LINKED::contains)) {
            return false;
        }
        // One identity per provider per account (uq_identity_user_provider). A different subject
        // from the same provider claiming this email is a different provider account, and the
        // insert would fail on that constraint anyway.
        return identities.findByUserIdAndProvider(user.getId(), profile.provider()).isEmpty();
    }

    private UserIdentity buildIdentity(ProviderProfile p, User user) {
        UserIdentity id = UserIdentity.builder()
                .user(user)
                .provider(p.provider())
                .providerUserId(p.providerUserId())
                .emailAtProvider(p.email())
                .emailVerified(p.emailVerified())
                .displayName(p.displayName())
                .avatarUrl(p.avatarUrl())
                .privateEmail(p.privateEmail())
                .rawProfile(p.rawProfile())
                .linkedAt(Instant.now())
                .lastLoginAt(Instant.now())
                .build();
        id.setId(UUID.randomUUID());
        return id;
    }

    /** Facebook may omit email and Apple may suppress it; we keep accounts unique with a synthetic. */
    private static String syntheticEmail(ProviderProfile p) {
        return p.provider().name().toLowerCase() + "+" + p.providerUserId() + "@noemail.local";
    }

    private static String orDefault(String s, String d) {
        return (s == null || s.isBlank()) ? d : s;
    }

    /**
     * Provider-neutral profile snapshot. {@code emailVerified} is true only when the provider
     * itself asserts that the email is verified; it decides whether rule 2 may link by email.
     */
    public record ProviderProfile(
            AuthProvider provider,
            String providerUserId,
            String email,
            boolean emailVerified,
            boolean privateEmail,
            String firstName,
            String lastName,
            String displayName,
            String avatarUrl,
            Map<String, Object> rawProfile
    ) {}
}
