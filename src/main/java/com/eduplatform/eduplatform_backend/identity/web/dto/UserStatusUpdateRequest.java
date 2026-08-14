package com.eduplatform.eduplatform_backend.identity.web.dto;

import jakarta.validation.constraints.NotBlank;

/** Toggle a user's account status from the admin dashboard (ACTIVE / DISABLED). */
public record UserStatusUpdateRequest(@NotBlank String status) {}
