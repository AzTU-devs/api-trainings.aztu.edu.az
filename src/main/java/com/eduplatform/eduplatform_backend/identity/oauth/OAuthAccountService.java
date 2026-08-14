package com.eduplatform.eduplatform_backend.identity.oauth;

import com.eduplatform.eduplatform_backend.common.enums.AuthProvider;
import com.eduplatform.eduplatform_backend.common.enums.RoleCode;
import com.eduplatform.eduplatform_backend.common.enums.UserStatus;
import com.eduplatform.eduplatform_backend.identity.domain.Role;
import com.eduplatform.eduplatform_backend.identity.domain.User;
import com.eduplatform.eduplatform_backend.identity.domain.UserIdentity;
import com.eduplatform.eduplatform_backend.identity.domain.UserRole;
import com.eduplatform.eduplatform_backend.identity.domain.UserRoleId;
import com.eduplatform.eduplatform_backend.identity.repo.RoleRepository;
import com.eduplatform.eduplatform_backend.identity.repo.UserIdentityRepository;
import com.eduplatform.eduplatform_backend.identity.repo.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Resolve-or-create a {@link User} given a provider's profile.
 *
 * Rules (mirror docs/architecture/01-erd-and-schema.md §12):
 *   1. Provider identity already linked → log that user in.
 *   2. Provider identity not linked but {@code emailVerified == true} and a user with this email exists
 *      → auto-link (create a new {@link UserIdentity} pointing at that user).
 *   3. Otherwise → create a new user with {@code password_hash = NULL} and link the identity.
 *
 * Never auto-link on an unverified email (account-takeover vector).
 */
@Service
public class OAuthAccountService {

    private final UserRepository users;
    private final UserIdentityRepository identities;
    private final RoleRepository roles;

    public OAuthAccountService(UserRepository users, UserIdentityRepository identities, RoleRepository roles) {
        this.users = users;
        this.identities = identities;
        this.roles = roles;
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

        // Rule 2: auto-link on verified email match
        if (profile.email() != null && profile.emailVerified()) {
            Optional<User> matched = users.findByEmailIgnoreCase(profile.email());
            if (matched.isPresent()) {
                User user = matched.get();
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
        users.save(user);

        Role userRole = roles.findByCode(RoleCode.USER)
                .orElseThrow(() -> new IllegalStateException("Role USER missing"));
        UserRole link = UserRole.builder()
                .id(new UserRoleId(user.getId(), userRole.getId()))
                .user(user)
                .role(userRole)
                .grantedAt(Instant.now())
                .build();
        user.getUserRoles().add(link);

        identities.save(buildIdentity(profile, user));
        return user;
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

    /** Provider-neutral profile snapshot. */
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
