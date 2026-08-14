package com.eduplatform.eduplatform_backend.notification.web.dto;

import com.eduplatform.eduplatform_backend.common.enums.NotificationChannel;
import com.eduplatform.eduplatform_backend.common.enums.NotificationStatus;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public record NotificationDto(
        UUID id,
        String templateCode,
        NotificationChannel channel,
        String title,
        String body,
        Map<String, Object> payload,
        NotificationStatus status,
        Instant sentAt,
        Instant readAt,
        Instant createdAt
) {}
