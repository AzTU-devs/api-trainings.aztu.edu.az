package com.eduplatform.eduplatform_backend.tutor.web;

import com.eduplatform.eduplatform_backend.common.web.ApiResponse;
import com.eduplatform.eduplatform_backend.tutor.service.TutorService;
import com.eduplatform.eduplatform_backend.tutor.web.dto.TutorProfileDto;
import com.eduplatform.eduplatform_backend.tutor.web.mapper.TutorMapper;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/public/tutors")
@Tag(name = "Public — Tutors")
public class TutorPublicController {

    private final TutorService service;
    private final TutorMapper mapper;

    public TutorPublicController(TutorService service, TutorMapper mapper) {
        this.service = service;
        this.mapper = mapper;
    }

    @GetMapping("/{id}")
    @Operation(summary = "Public profile of an approved tutor", security = {})
    public ApiResponse<TutorProfileDto> get(@PathVariable UUID id) {
        return ApiResponse.ok(mapper.toDto(service.publicProfile(id)));
    }
}
