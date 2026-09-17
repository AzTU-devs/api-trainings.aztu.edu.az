package com.eduplatform.eduplatform_backend.course.web;

import com.eduplatform.eduplatform_backend.common.enums.CourseLevel;
import com.eduplatform.eduplatform_backend.common.enums.CourseType;
import com.eduplatform.eduplatform_backend.common.web.ApiResponse;
import com.eduplatform.eduplatform_backend.common.web.PageResponse;
import com.eduplatform.eduplatform_backend.course.repo.CourseCatalogFilter;
import com.eduplatform.eduplatform_backend.course.service.CourseService;
import com.eduplatform.eduplatform_backend.course.web.dto.CourseDto;
import com.eduplatform.eduplatform_backend.course.web.dto.CourseSummaryDto;
import com.eduplatform.eduplatform_backend.course.web.mapper.CourseMapper;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.data.domain.Pageable;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.UUID;

@RestController
@RequestMapping("/api/public/courses")
@Tag(name = "Public — Courses")
public class CoursePublicController {

    private final CourseService service;
    private final CourseMapper mapper;

    public CoursePublicController(CourseService service, CourseMapper mapper) {
        this.service = service;
        this.mapper = mapper;
    }

    @GetMapping
    @Operation(summary = "Browse published courses; every parameter is optional and they combine",
               security = {})
    public ApiResponse<PageResponse<CourseSummaryDto>> browse(
            @Parameter(description = "Free text over title, subtitle and description")
            @RequestParam(required = false) String q,
            @RequestParam(required = false) CourseType type,
            @RequestParam(required = false) UUID categoryId,
            @RequestParam(required = false) CourseLevel level,
            @Parameter(description = "Language code, e.g. az or en")
            @RequestParam(required = false) String language,
            @RequestParam(required = false) Boolean free,
            @RequestParam(required = false) BigDecimal priceMin,
            @RequestParam(required = false) BigDecimal priceMax,
            @Parameter(description = "Minimum average rating, 0..5")
            @RequestParam(required = false) BigDecimal ratingMin,
            @Parameter(description = "Course length bucket: lt2, 2to6, 6to17 or gt17")
            @RequestParam(required = false) String durationBucket,
            Pageable pageable) {
        CourseCatalogFilter filter = new CourseCatalogFilter(
                q, type, categoryId, level, language, free, priceMin, priceMax, ratingMin,
                CourseCatalogFilter.DurationBucket.fromWireValue(durationBucket));
        return ApiResponse.ok(PageResponse.of(service.browsePublished(filter, pageable), mapper::toSummaryDto));
    }

    @GetMapping("/search")
    @Operation(summary = "Full-text search over published courses", security = {})
    public ApiResponse<PageResponse<CourseSummaryDto>> search(
            @RequestParam("q") String query,
            Pageable pageable) {
        // Alias of browse with only q set — kept because the admin portal calls it.
        return ApiResponse.ok(PageResponse.of(service.search(query, pageable), mapper::toSummaryDto));
    }

    @GetMapping("/{slug}")
    @Operation(summary = "Course detail by slug", security = {})
    public ApiResponse<CourseDto> bySlug(@PathVariable String slug) {
        return ApiResponse.ok(mapper.toDto(service.getBySlug(slug)));
    }
}
