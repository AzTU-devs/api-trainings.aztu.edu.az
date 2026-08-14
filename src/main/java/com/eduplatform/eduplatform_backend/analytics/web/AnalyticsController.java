package com.eduplatform.eduplatform_backend.analytics.web;

import com.eduplatform.eduplatform_backend.analytics.service.AnalyticsService;
import com.eduplatform.eduplatform_backend.analytics.web.dto.AdminDashboardDto;
import com.eduplatform.eduplatform_backend.analytics.web.dto.AnalyticsOverviewDto;
import com.eduplatform.eduplatform_backend.common.web.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/analytics")
@Tag(name = "Admin — Analytics")
@PreAuthorize("hasAuthority('analytics:read')")
public class AnalyticsController {

    private final AnalyticsService service;

    public AnalyticsController(AnalyticsService service) {
        this.service = service;
    }

    @GetMapping("/overview")
    @Operation(summary = "Platform analytics overview (range = 7d | 30d | 90d)")
    public ApiResponse<AnalyticsOverviewDto> overview(@RequestParam(required = false) String range) {
        return ApiResponse.ok(service.overview(range));
    }

    @GetMapping("/dashboard")
    @Operation(summary = "Admin dashboard headline counters")
    public ApiResponse<AdminDashboardDto> dashboard() {
        return ApiResponse.ok(service.adminDashboard());
    }
}
