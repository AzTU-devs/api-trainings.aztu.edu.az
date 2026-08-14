package com.eduplatform.eduplatform_backend.course.web;

import com.eduplatform.eduplatform_backend.common.enums.CourseStatus;
import com.eduplatform.eduplatform_backend.common.security.AuthenticatedPrincipal;
import com.eduplatform.eduplatform_backend.common.security.CurrentUser;
import com.eduplatform.eduplatform_backend.common.web.ApiResponse;
import com.eduplatform.eduplatform_backend.common.web.PageResponse;
import com.eduplatform.eduplatform_backend.course.service.CourseService;
import com.eduplatform.eduplatform_backend.course.web.dto.CourseDto;
import com.eduplatform.eduplatform_backend.course.web.dto.CourseSummaryDto;
import com.eduplatform.eduplatform_backend.course.web.mapper.CourseMapper;
import com.eduplatform.eduplatform_backend.tutor.web.dto.ApprovalDecisionRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/admin/courses")
@Tag(name = "Admin — Courses")
public class CourseAdminController {

    private final CourseService service;
    private final CourseMapper mapper;

    public CourseAdminController(CourseService service, CourseMapper mapper) {
        this.service = service;
        this.mapper = mapper;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('course:approve')")
    @Operation(summary = "List courses by status (moderation queue; defaults to IN_REVIEW)")
    public ApiResponse<PageResponse<CourseSummaryDto>> list(
            @RequestParam(defaultValue = "IN_REVIEW") CourseStatus status,
            Pageable pageable) {
        return ApiResponse.ok(PageResponse.of(service.listByStatus(status, pageable), mapper::toSummaryDto));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('course:approve')")
    @Operation(summary = "Full course detail for moderation (any status, by id)")
    public ApiResponse<CourseDto> get(@PathVariable UUID id) {
        return ApiResponse.ok(mapper.toDto(service.get(id)));
    }

    @PostMapping("/{id}/decision")
    @PreAuthorize("hasAuthority('course:approve')")
    @Operation(summary = "Approve or reject a course awaiting review")
    public ApiResponse<CourseDto> decide(@PathVariable UUID id,
                                         @Valid @RequestBody ApprovalDecisionRequest req,
                                         @CurrentUser AuthenticatedPrincipal me) {
        return ApiResponse.ok(mapper.toDto(service.adminDecide(id, me.userId(), req.decision(), req.note())));
    }
}
