package com.eduplatform.eduplatform_backend.course.web;

import com.eduplatform.eduplatform_backend.common.enums.CourseStatus;
import com.eduplatform.eduplatform_backend.common.security.AuthenticatedPrincipal;
import com.eduplatform.eduplatform_backend.common.security.CurrentUser;
import com.eduplatform.eduplatform_backend.common.web.ApiResponse;
import com.eduplatform.eduplatform_backend.common.web.PageResponse;
import com.eduplatform.eduplatform_backend.course.service.CourseService;
import com.eduplatform.eduplatform_backend.course.web.dto.AdminCreateCourseRequest;
import com.eduplatform.eduplatform_backend.course.web.dto.CourseDto;
import com.eduplatform.eduplatform_backend.course.web.dto.CourseSummaryDto;
import com.eduplatform.eduplatform_backend.course.web.dto.SetCourseTutorsRequest;
import com.eduplatform.eduplatform_backend.course.web.mapper.CourseMapper;
import com.eduplatform.eduplatform_backend.tutor.web.dto.ApprovalDecisionRequest;
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

    @PostMapping
    @PreAuthorize("hasAuthority('course:create_any')")
    @Operation(summary = "Create a course on behalf of the university and assign its tutors",
            description = "Super-admin only. Unlike the tutor endpoint the tutors are stated explicitly, "
                    + "since the course belongs to the university rather than to whoever creates it. "
                    + "authorizedTutorId nominates the single tutor permitted to edit the course and "
                    + "must be one of tutorIds.")
    public ResponseEntity<ApiResponse<CourseDto>> create(@Valid @RequestBody AdminCreateCourseRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.ok(mapper.toDto(service.createByAdmin(req))));
    }

    @PutMapping("/{id}/tutors")
    @PreAuthorize("hasAuthority('course:manage')")
    @Operation(summary = "Replace a course's teaching roster and nominate its authorised editor",
            description = "Full replacement: tutors omitted from tutorIds are removed from the course.")
    public ApiResponse<CourseDto> setTutors(@PathVariable UUID id,
                                            @Valid @RequestBody SetCourseTutorsRequest req) {
        return ApiResponse.ok(mapper.toDto(service.setTutors(id, req)));
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
