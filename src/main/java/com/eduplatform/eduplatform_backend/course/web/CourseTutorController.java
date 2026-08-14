package com.eduplatform.eduplatform_backend.course.web;

import com.eduplatform.eduplatform_backend.common.enums.CourseStatus;
import com.eduplatform.eduplatform_backend.common.security.AuthenticatedPrincipal;
import com.eduplatform.eduplatform_backend.common.security.CurrentUser;
import com.eduplatform.eduplatform_backend.common.web.ApiResponse;
import com.eduplatform.eduplatform_backend.common.web.PageResponse;
import com.eduplatform.eduplatform_backend.course.service.CourseService;
import com.eduplatform.eduplatform_backend.course.web.dto.CourseDto;
import com.eduplatform.eduplatform_backend.course.web.dto.CourseSummaryDto;
import com.eduplatform.eduplatform_backend.course.web.dto.CreateCourseRequest;
import com.eduplatform.eduplatform_backend.course.web.dto.UpdateCourseRequest;
import com.eduplatform.eduplatform_backend.course.web.mapper.CourseMapper;
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
@RequestMapping("/api/portal/courses")
@Tag(name = "Portal — Courses (tutor)")
public class CourseTutorController {

    private final CourseService service;
    private final CourseMapper mapper;

    public CourseTutorController(CourseService service, CourseMapper mapper) {
        this.service = service;
        this.mapper = mapper;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('course:read')")
    @Operation(summary = "List my own courses (all statuses), optionally filtered by status")
    public ApiResponse<PageResponse<CourseSummaryDto>> mine(
            @RequestParam(required = false) CourseStatus status,
            @CurrentUser AuthenticatedPrincipal me,
            Pageable pageable) {
        return ApiResponse.ok(PageResponse.of(service.listMine(me.userId(), status, pageable), mapper::toSummaryDto));
    }

    @PostMapping
    @PreAuthorize("hasAuthority('course:create')")
    @Operation(summary = "Create a new course as the current tutor")
    public ResponseEntity<ApiResponse<CourseDto>> create(@Valid @RequestBody CreateCourseRequest req,
                                                         @CurrentUser AuthenticatedPrincipal me) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.ok(mapper.toDto(service.createByTutor(me.userId(), req))));
    }

    @PatchMapping("/{id}")
    @PreAuthorize("hasAuthority('course:update_own')")
    @Operation(summary = "Update a course (partial)")
    public ApiResponse<CourseDto> update(@PathVariable UUID id,
                                         @Valid @RequestBody UpdateCourseRequest req,
                                         @CurrentUser AuthenticatedPrincipal me) {
        return ApiResponse.ok(mapper.toDto(service.updateByTutor(me.userId(), id, req)));
    }

    @PostMapping("/{id}/submit")
    @PreAuthorize("hasAuthority('course:update_own')")
    @Operation(summary = "Submit a course for admin approval")
    public ApiResponse<CourseDto> submit(@PathVariable UUID id, @CurrentUser AuthenticatedPrincipal me) {
        return ApiResponse.ok(mapper.toDto(service.submitForReview(me.userId(), id)));
    }

    @PostMapping("/{id}/archive")
    @PreAuthorize("hasAuthority('course:update_own')")
    @Operation(summary = "Archive a course")
    public ResponseEntity<Void> archive(@PathVariable UUID id, @CurrentUser AuthenticatedPrincipal me) {
        service.archive(me.userId(), id);
        return ResponseEntity.noContent().build();
    }
}
