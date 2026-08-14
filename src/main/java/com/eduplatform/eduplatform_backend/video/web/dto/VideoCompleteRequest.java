package com.eduplatform.eduplatform_backend.video.web.dto;

import jakarta.validation.constraints.Size;

public record VideoCompleteRequest(
        @Size(max = 200) String title
) {}
