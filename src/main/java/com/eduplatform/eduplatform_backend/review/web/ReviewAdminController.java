package com.eduplatform.eduplatform_backend.review.web;

import com.eduplatform.eduplatform_backend.common.web.ApiResponse;
import com.eduplatform.eduplatform_backend.common.web.PageResponse;
import com.eduplatform.eduplatform_backend.review.service.ReviewService;
import com.eduplatform.eduplatform_backend.review.web.dto.CourseReviewDto;
import com.eduplatform.eduplatform_backend.review.web.dto.ReviewVisibilityRequest;
import com.eduplatform.eduplatform_backend.review.web.mapper.ReviewMapper;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/**
 * Review moderation. review:moderate has been granted to ADMIN since V2 without anything to
 * exercise it on, so an abusive public review could not be taken down at all.
 */
@RestController
@RequestMapping("/api/admin/reviews")
@Tag(name = "Admin — Reviews")
@PreAuthorize("hasAuthority('review:moderate')")
public class ReviewAdminController {

    private final ReviewService service;
    private final ReviewMapper mapper;

    public ReviewAdminController(ReviewService service, ReviewMapper mapper) {
        this.service = service;
        this.mapper = mapper;
    }

    @GetMapping
    @Operation(summary = "List reviews for moderation, hidden ones included, newest first",
            description = "Filter by courseId and/or visible; both are optional.")
    public ApiResponse<PageResponse<CourseReviewDto>> list(@RequestParam(required = false) UUID courseId,
                                                          @RequestParam(required = false) Boolean visible,
                                                          @PageableDefault(size = 20) Pageable pageable) {
        return ApiResponse.ok(PageResponse.of(service.forModeration(courseId, visible, pageable), mapper::toDto));
    }

    @PatchMapping("/{id}/visibility")
    @Operation(summary = "Hide a review from the course page, or show it again",
            description = "The course's rating is recomputed from its visible reviews.")
    public ApiResponse<CourseReviewDto> setVisibility(@PathVariable UUID id,
                                                      @Valid @RequestBody ReviewVisibilityRequest req) {
        return ApiResponse.ok(mapper.toDto(service.setVisible(id, req.visible())));
    }
}
