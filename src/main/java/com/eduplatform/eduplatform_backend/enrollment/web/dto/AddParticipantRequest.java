package com.eduplatform.eduplatform_backend.enrollment.web.dto;

import jakarta.validation.constraints.Email;

import java.util.UUID;

/**
 * Identifies the account to place on a course, by {@code userId} or by {@code email} — the
 * dashboard has the id when it picks somebody out of the user list and only an email when an
 * admin types one in. {@code userId} wins when both are sent; neither is a 400.
 */
public record AddParticipantRequest(
        UUID userId,
        @Email String email
) {}
