package com.eduplatform.eduplatform_backend.enrollment.web;

import com.eduplatform.eduplatform_backend.common.enums.EnrollmentSource;
import com.eduplatform.eduplatform_backend.common.security.AuthenticatedPrincipal;
import com.eduplatform.eduplatform_backend.common.security.CurrentUser;
import com.eduplatform.eduplatform_backend.common.web.ApiResponse;
import com.eduplatform.eduplatform_backend.common.web.PageResponse;
import com.eduplatform.eduplatform_backend.enrollment.service.EnrollmentService;
import com.eduplatform.eduplatform_backend.enrollment.web.dto.EnrollmentDto;
import com.eduplatform.eduplatform_backend.enrollment.web.dto.LessonProgressDto;
import com.eduplatform.eduplatform_backend.enrollment.web.dto.LessonProgressUpdateRequest;
import com.eduplatform.eduplatform_backend.enrollment.web.mapper.EnrollmentMapper;
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
@RequestMapping("/api/portal/enrollments")
@Tag(name = "Portal — Enrollments")
public class EnrollmentController {

    private final EnrollmentService service;
    private final EnrollmentMapper mapper;

    public EnrollmentController(EnrollmentService service, EnrollmentMapper mapper) {
        this.service = service;
        this.mapper = mapper;
    }

    @PostMapping("/courses/{courseId}/free")
    @PreAuthorize("hasAuthority('enrollment:create')")
    @Operation(summary = "Enrol the current user in a free course")
    public ResponseEntity<ApiResponse<EnrollmentDto>> enrollFree(@PathVariable UUID courseId,
                                                                 @CurrentUser AuthenticatedPrincipal me) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.ok(mapper.toDto(service.enroll(me.userId(), courseId, EnrollmentSource.FREE))));
    }

    @GetMapping("/mine")
    @PreAuthorize("hasAuthority('enrollment:read_own')")
    @Operation(summary = "List my enrollments")
    public ApiResponse<PageResponse<EnrollmentDto>> mine(@CurrentUser AuthenticatedPrincipal me, Pageable pageable) {
        return ApiResponse.ok(PageResponse.of(service.mine(me.userId(), pageable), mapper::toDto));
    }

    @GetMapping("/courses/{courseId}/progress")
    @PreAuthorize("hasAuthority('enrollment:read_own')")
    @Operation(summary = "Get my saved lesson progress for a course")
    public ApiResponse<java.util.List<LessonProgressDto>> courseProgress(@PathVariable UUID courseId,
                                                                         @CurrentUser AuthenticatedPrincipal me) {
        return ApiResponse.ok(service.courseProgress(me.userId(), courseId).stream()
                .map(mapper::toLessonProgressDto).toList());
    }

    @PutMapping("/courses/{courseId}/lessons/{lessonId}/progress")
    @PreAuthorize("hasAuthority('enrollment:read_own')")
    @Operation(summary = "Update lesson progress")
    public ApiResponse<LessonProgressDto> updateProgress(@PathVariable UUID courseId,
                                                         @PathVariable UUID lessonId,
                                                         @Valid @RequestBody LessonProgressUpdateRequest req,
                                                         @CurrentUser AuthenticatedPrincipal me) {
        return ApiResponse.ok(mapper.toLessonProgressDto(
                service.updateProgress(me.userId(), courseId, lessonId, req)));
    }
}
