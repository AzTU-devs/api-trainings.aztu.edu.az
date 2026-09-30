package com.eduplatform.eduplatform_backend.tutor.web;

import com.eduplatform.eduplatform_backend.common.web.ApiResponse;
import com.eduplatform.eduplatform_backend.common.web.PageResponse;
import com.eduplatform.eduplatform_backend.tutor.service.TutorService;
import com.eduplatform.eduplatform_backend.tutor.web.dto.TutorPublicProfileDto;
import com.eduplatform.eduplatform_backend.tutor.web.mapper.TutorMapper;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
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

    /**
     * The public expert directory. The website used to rebuild it from the published catalogue,
     * which left out every approved expert who had no published course yet — while the catalogue
     * showed sample content, that was nearly all of them.
     *
     * <p>Only the page and its size come from the caller. A caller-chosen sort is ignored rather
     * than passed to the query: this endpoint is anonymous, and Spring Data would accept a nested
     * property such as {@code user.email}, turning the order of the results into an oracle for
     * account fields the page never shows.
     */
    @GetMapping
    @Operation(summary = "Approved experts, by name, for the public expert directory", security = {})
    public ApiResponse<PageResponse<TutorPublicProfileDto>> list(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        return ApiResponse.ok(PageResponse.of(service.listPublic(page, size), mapper::toPublicDto));
    }

    @GetMapping("/{id}")
    @Operation(summary = "Public profile of an approved tutor", security = {})
    public ApiResponse<TutorPublicProfileDto> get(@PathVariable UUID id) {
        return ApiResponse.ok(mapper.toPublicDto(service.publicProfile(id)));
    }
}
