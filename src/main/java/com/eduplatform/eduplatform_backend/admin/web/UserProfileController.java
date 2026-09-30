package com.eduplatform.eduplatform_backend.admin.web;

import com.eduplatform.eduplatform_backend.admin.service.UserProfileService;
import com.eduplatform.eduplatform_backend.admin.web.dto.UserProfileDto;
import com.eduplatform.eduplatform_backend.common.web.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * The super admin's inspection of a single account, participant or expert alike. Behind its own
 * permission, {@code user:inspect}, which V22 grants to SUPER_ADMIN only: ADMIN manages accounts
 * ({@code user:manage}) but does not get to read everyone's orders, sessions and audit trail.
 */
@RestController
@RequestMapping("/api/super/users")
@Tag(name = "Super — Users")
@PreAuthorize("hasAuthority('user:inspect')")
public class UserProfileController {

    private final UserProfileService service;

    public UserProfileController(UserProfileService service) {
        this.service = service;
    }

    @GetMapping("/{userId}/profile")
    @Operation(summary = "Everything held about one account, deleted ones included",
            description = "The account and its linked sign-in methods; the expert profile, with its courses, "
                    + "approval history and room bookings, or null; the participant's enrolments, orders and "
                    + "reviews; the newest 20 sessions, 50 security events and 50 audit entries. No password, "
                    + "token or provider secret is ever included. A deleted account is returned with "
                    + "account.deletedAt set; an id that never existed is 404 USER_NOT_FOUND.")
    public ApiResponse<UserProfileDto> profile(@PathVariable UUID userId) {
        return ApiResponse.ok(service.profile(userId));
    }
}
