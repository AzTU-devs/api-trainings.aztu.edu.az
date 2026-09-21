package com.eduplatform.eduplatform_backend.enrollment.web;

import com.eduplatform.eduplatform_backend.common.enums.EnrollmentStatus;
import com.eduplatform.eduplatform_backend.common.web.ApiResponse;
import com.eduplatform.eduplatform_backend.common.web.PageResponse;
import com.eduplatform.eduplatform_backend.enrollment.service.EnrollmentService;
import com.eduplatform.eduplatform_backend.enrollment.web.dto.AddParticipantRequest;
import com.eduplatform.eduplatform_backend.enrollment.web.dto.CourseParticipantDto;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/** Who is on a course, and who puts them there — the dashboard's participants screen. */
@RestController
@RequestMapping("/api/admin/courses/{courseId}/participants")
@Tag(name = "Admin — Course participants")
public class CourseParticipantAdminController {

    private final EnrollmentService service;

    public CourseParticipantAdminController(EnrollmentService service) {
        this.service = service;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('enrollment:manage')")
    @Operation(summary = "List a course's participants, newest grant first",
            description = "Every enrolment status by default, so a revoked place is still listed "
                    + "as CANCELLED; pass status to narrow it.")
    public ApiResponse<PageResponse<CourseParticipantDto>> list(
            @PathVariable UUID courseId,
            @RequestParam(required = false) EnrollmentStatus status,
            @PageableDefault(size = 20) Pageable pageable) {
        return ApiResponse.ok(PageResponse.of(service.listParticipants(courseId, status, pageable)));
    }

    @PostMapping
    @PreAuthorize("hasAuthority('enrollment:manage')")
    @Operation(summary = "Give a user a place on the course",
            description = "Identify the account by userId or by email. The grant bypasses payment "
                    + "and publication, and somebody who already holds a place is returned as they "
                    + "are. An email with no account behind it is a 404 PARTICIPANT_EMAIL_NOT_REGISTERED.")
    public ResponseEntity<ApiResponse<CourseParticipantDto>> add(@PathVariable UUID courseId,
                                                                 @Valid @RequestBody AddParticipantRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.ok(service.addParticipant(courseId, req)));
    }

    @DeleteMapping("/{userId}")
    @PreAuthorize("hasAuthority('enrollment:manage')")
    @Operation(summary = "Take a participant off the course",
            description = "Cancels the enrolment; progress, attendance and certificates are kept.")
    public ResponseEntity<Void> remove(@PathVariable UUID courseId, @PathVariable UUID userId) {
        service.removeParticipant(courseId, userId);
        return ResponseEntity.noContent().build();
    }
}
