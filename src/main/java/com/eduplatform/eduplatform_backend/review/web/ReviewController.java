package com.eduplatform.eduplatform_backend.review.web;

import com.eduplatform.eduplatform_backend.common.security.AuthenticatedPrincipal;
import com.eduplatform.eduplatform_backend.common.security.CurrentUser;
import com.eduplatform.eduplatform_backend.common.web.ApiResponse;
import com.eduplatform.eduplatform_backend.common.web.PageResponse;
import com.eduplatform.eduplatform_backend.review.service.ReviewService;
import com.eduplatform.eduplatform_backend.review.web.dto.CourseReviewDto;
import com.eduplatform.eduplatform_backend.review.web.dto.ReviewCreateRequest;
import com.eduplatform.eduplatform_backend.review.web.mapper.ReviewMapper;
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
@Tag(name = "Reviews")
public class ReviewController {

    private final ReviewService service;
    private final ReviewMapper mapper;

    public ReviewController(ReviewService service, ReviewMapper mapper) {
        this.service = service;
        this.mapper = mapper;
    }

    @GetMapping("/api/public/courses/{courseId}/reviews")
    @Operation(summary = "List reviews for a course", security = {})
    public ApiResponse<PageResponse<CourseReviewDto>> list(@PathVariable UUID courseId, Pageable pageable) {
        return ApiResponse.ok(PageResponse.of(service.forCourse(courseId, pageable), mapper::toDto));
    }

    @PostMapping("/api/portal/courses/{courseId}/reviews")
    @PreAuthorize("hasAuthority('review:create')")
    @Operation(summary = "Write a review for an enrolled course")
    public ResponseEntity<ApiResponse<CourseReviewDto>> create(@PathVariable UUID courseId,
                                                               @Valid @RequestBody ReviewCreateRequest req,
                                                               @CurrentUser AuthenticatedPrincipal me) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.ok(mapper.toDto(service.create(me.userId(), courseId, req))));
    }
}
