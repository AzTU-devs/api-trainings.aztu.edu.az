package com.eduplatform.eduplatform_backend.notification.web.dto;

import com.eduplatform.eduplatform_backend.common.enums.RoleCode;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.Set;
import java.util.UUID;

/** Admin broadcast request — fan out an in-app notification to a target audience. */
public record BroadcastRequest(
        @NotBlank String title,
        @NotBlank String body,
        @NotNull Target target,
        RoleCode role,
        Set<UUID> userIds
) {
    public enum Target { ALL, ROLE, USERS }
}
