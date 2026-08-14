package com.eduplatform.eduplatform_backend.tutor.web;

import com.eduplatform.eduplatform_backend.analytics.service.AnalyticsService;
import com.eduplatform.eduplatform_backend.analytics.web.dto.TutorDashboardDto;
import com.eduplatform.eduplatform_backend.common.enums.TutorApprovalStatus;
import com.eduplatform.eduplatform_backend.common.security.AuthenticatedPrincipal;
import com.eduplatform.eduplatform_backend.common.security.CurrentUser;
import com.eduplatform.eduplatform_backend.common.web.ApiResponse;
import com.eduplatform.eduplatform_backend.common.web.PageResponse;
import com.eduplatform.eduplatform_backend.tutor.service.TutorService;
import com.eduplatform.eduplatform_backend.tutor.web.dto.ApprovalDecisionRequest;
import com.eduplatform.eduplatform_backend.tutor.web.dto.TutorApplyRequest;
import com.eduplatform.eduplatform_backend.tutor.web.dto.TutorProfileDto;
import com.eduplatform.eduplatform_backend.tutor.web.dto.TutorStudentDto;
import com.eduplatform.eduplatform_backend.tutor.web.mapper.TutorMapper;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/portal/tutor")
@Tag(name = "Portal — Tutor")
public class TutorController {

    private final TutorService service;
    private final TutorMapper mapper;
    private final AnalyticsService analytics;

    public TutorController(TutorService service, TutorMapper mapper, AnalyticsService analytics) {
        this.service = service;
        this.mapper = mapper;
        this.analytics = analytics;
    }

    @GetMapping("/dashboard")
    @PreAuthorize("hasAuthority('course:create')")
    @Operation(summary = "Tutor dashboard headline counters")
    public ApiResponse<TutorDashboardDto> dashboard(@CurrentUser AuthenticatedPrincipal me) {
        return ApiResponse.ok(analytics.tutorDashboard(me.userId()));
    }

    @PostMapping("/apply")
    @PreAuthorize("hasAuthority('tutor:apply')")
    @Operation(summary = "Apply to become a tutor")
    public ResponseEntity<ApiResponse<TutorProfileDto>> apply(@Valid @RequestBody TutorApplyRequest req,
                                                              @CurrentUser AuthenticatedPrincipal me) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.ok(mapper.toDto(service.apply(me.userId(), req))));
    }

    @GetMapping("/me")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Get my tutor profile")
    public ApiResponse<TutorProfileDto> me(@CurrentUser AuthenticatedPrincipal me) {
        return ApiResponse.ok(mapper.toDto(service.myProfile(me.userId())));
    }

    @GetMapping("/students")
    @PreAuthorize("hasAuthority('course:create')")
    @Operation(summary = "List students enrolled in my courses (aggregated per student)")
    public ApiResponse<PageResponse<TutorStudentDto>> myStudents(
            @RequestParam(required = false) String search,
            @RequestParam(required = false) UUID courseId,
            @CurrentUser AuthenticatedPrincipal me,
            Pageable pageable) {
        return ApiResponse.ok(PageResponse.of(service.listMyStudents(me.userId(), search, courseId, pageable)));
    }

    // --- admin ---

    @GetMapping("/admin")
    @PreAuthorize("hasAuthority('tutor:approve')")
    @Operation(summary = "List tutor profiles by approval status")
    public ApiResponse<PageResponse<TutorProfileDto>> list(
            @RequestParam(defaultValue = "PENDING") TutorApprovalStatus status,
            Pageable pageable) {
        return ApiResponse.ok(PageResponse.of(service.listByStatus(status, pageable), mapper::toDto));
    }

    @PostMapping("/admin/{tutorId}/decision")
    @PreAuthorize("hasAuthority('tutor:approve')")
    @Operation(summary = "Approve or reject a tutor application")
    public ApiResponse<TutorProfileDto> decide(@PathVariable UUID tutorId,
                                               @Valid @RequestBody ApprovalDecisionRequest req,
                                               @CurrentUser AuthenticatedPrincipal me) {
        return ApiResponse.ok(mapper.toDto(service.decide(tutorId, me.userId(), req)));
    }
}
