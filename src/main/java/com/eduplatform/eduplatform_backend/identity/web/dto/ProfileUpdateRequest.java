package com.eduplatform.eduplatform_backend.identity.web.dto;

import jakarta.validation.constraints.Size;

/** Self-service profile update for the currently authenticated user. */
public record ProfileUpdateRequest(
        @Size(max = 80) String firstName,
        @Size(max = 80) String lastName,
        @Size(max = 32) String phone,
        @Size(max = 8) String locale
) {}
