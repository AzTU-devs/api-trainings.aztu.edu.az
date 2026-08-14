package com.eduplatform.eduplatform_backend.audit.web;

import com.eduplatform.eduplatform_backend.audit.service.SystemService;
import com.eduplatform.eduplatform_backend.audit.web.dto.SystemHealthDto;
import com.eduplatform.eduplatform_backend.common.web.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/super/system")
@Tag(name = "Super — System monitoring")
@PreAuthorize("hasAuthority('system:read')")
public class SystemController {

    private final SystemService service;

    public SystemController(SystemService service) {
        this.service = service;
    }

    @GetMapping("/health")
    @Operation(summary = "Runtime/system health snapshot")
    public ApiResponse<SystemHealthDto> health() {
        return ApiResponse.ok(service.health());
    }
}
