package com.eduplatform.eduplatform_backend.tutor.web;

import com.eduplatform.eduplatform_backend.common.security.AuthenticatedPrincipal;
import com.eduplatform.eduplatform_backend.common.security.CurrentUser;
import com.eduplatform.eduplatform_backend.common.web.ApiResponse;
import com.eduplatform.eduplatform_backend.tutor.service.TutorService;
import com.eduplatform.eduplatform_backend.tutor.web.dto.TutorProfileDto;
import com.eduplatform.eduplatform_backend.tutor.web.dto.UpdateTutorProfileRequest;
import com.eduplatform.eduplatform_backend.tutor.web.mapper.TutorMapper;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Admin maintenance of expert profiles. The application queue and the approve/reject decision
 * stay under {@code /api/portal/tutor/admin}; this is only for keeping a profile's details right.
 */
@RestController
@RequestMapping("/api/admin/tutors")
@Tag(name = "Admin — Tutors")
public class TutorAdminController {

    private final TutorService service;
    private final TutorMapper mapper;

    public TutorAdminController(TutorService service, TutorMapper mapper) {
        this.service = service;
        this.mapper = mapper;
    }

    @PatchMapping("/{tutorId}")
    @PreAuthorize("hasAuthority('tutor:manage')")
    @Operation(summary = "Edit any expert's profile (partial), whatever its approval status",
            description = "Same body, rules and audit as the expert's own PATCH /api/portal/tutor/me. "
                    + "avatarMediaId must be a READY image uploaded by the expert or by you. "
                    + "Approval status cannot be changed here; use the decision endpoint.")
    public ApiResponse<TutorProfileDto> update(@PathVariable UUID tutorId,
                                               @Valid @RequestBody UpdateTutorProfileRequest req,
                                               @CurrentUser AuthenticatedPrincipal me) {
        return ApiResponse.ok(mapper.toDto(service.updateProfileByAdmin(me, tutorId, req)));
    }
}
