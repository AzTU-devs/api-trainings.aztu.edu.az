package com.eduplatform.eduplatform_backend.identity.web.dto;

import com.eduplatform.eduplatform_backend.common.enums.TutorApprovalStatus;

import java.util.UUID;

/**
 * Result of a successful tutor OTP verification. No tokens are issued — the tutor must
 * be approved by an admin, then sign in via the portal.
 */
public record TutorRegisterResult(
        String message,
        UUID tutorId,
        TutorApprovalStatus approvalStatus
) {}
