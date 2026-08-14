package com.eduplatform.eduplatform_backend.audit.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record BlockIpRequest(
        @NotBlank @Size(max = 45) String ipAddress,
        @Size(max = 255) String reason
) {}
