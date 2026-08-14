package com.eduplatform.eduplatform_backend.audit.web;

import com.eduplatform.eduplatform_backend.audit.service.ApiLogService;
import com.eduplatform.eduplatform_backend.audit.web.dto.ApiLogEntryDto;
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

@RestController
@RequestMapping("/api/super/api-logs")
@Tag(name = "Super — API logs")
@PreAuthorize("hasAuthority('apilog:read')")
public class ApiLogController {

    private final ApiLogService service;

    public ApiLogController(ApiLogService service) {
        this.service = service;
    }

    @GetMapping
    @Operation(summary = "List HTTP API request logs (filterable, newest first)")
    public ApiResponse<PageResponse<ApiLogEntryDto>> list(
            @RequestParam(required = false) String search,
            @RequestParam(required = false) String method,
            @RequestParam(required = false) Integer statusMin,
            @RequestParam(required = false) Integer statusMax,
            @RequestParam(required = false, defaultValue = "false") boolean errorsOnly,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @PageableDefault(size = 20) Pageable pageable) {
        return ApiResponse.ok(PageResponse.of(
                service.list(search, method, statusMin, statusMax, errorsOnly, from, to, pageable)));
    }
}
