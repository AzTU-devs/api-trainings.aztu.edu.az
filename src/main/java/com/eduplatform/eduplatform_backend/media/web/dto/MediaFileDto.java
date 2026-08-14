package com.eduplatform.eduplatform_backend.media.web.dto;

import com.eduplatform.eduplatform_backend.common.enums.MediaStatus;
import com.eduplatform.eduplatform_backend.common.enums.MediaStorage;
import com.eduplatform.eduplatform_backend.common.enums.MediaVisibility;

import java.time.Instant;
import java.util.UUID;

public record MediaFileDto(
        UUID id,
        MediaStorage storage,
        String mimeType,
        long byteSize,
        Integer width,
        Integer height,
        Integer durationSec,
        MediaStatus status,
        MediaVisibility visibility,
        Instant createdAt
) {}
