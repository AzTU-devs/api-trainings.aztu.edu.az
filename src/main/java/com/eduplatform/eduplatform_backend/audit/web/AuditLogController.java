package com.eduplatform.eduplatform_backend.audit.web;

import com.eduplatform.eduplatform_backend.audit.service.AuditService;
import com.eduplatform.eduplatform_backend.audit.web.dto.AuditLogEntryDto;
import com.eduplatform.eduplatform_backend.common.web.ApiResponse;
import com.eduplatform.eduplatform_backend.common.web.PageResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.UUID;

@RestController
@RequestMapping("/api/super/audit-logs")
@Tag(name = "Super — Audit logs")
@PreAuthorize("hasAuthority('audit:read')")
public class AuditLogController {

    private final AuditService service;

    public AuditLogController(AuditService service) {
        this.service = service;
    }

    @GetMapping
    @Operation(summary = "List audit log entries (filterable, newest first)")
    public ApiResponse<PageResponse<AuditLogEntryDto>> list(
            @RequestParam(required = false) String search,
            @RequestParam(required = false) String action,
            @RequestParam(required = false) UUID actorId,
            @RequestParam(required = false) String resourceType,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @PageableDefault(size = 20) Pageable pageable) {
        return ApiResponse.ok(PageResponse.of(service.list(search, action, actorId, resourceType, from, to, pageable)));
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get one audit log entry")
    public ApiResponse<AuditLogEntryDto> get(@PathVariable UUID id) {
        return ApiResponse.ok(service.get(id));
    }
}
