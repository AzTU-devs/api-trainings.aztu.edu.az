package com.eduplatform.eduplatform_backend.course.web;

import com.eduplatform.eduplatform_backend.common.enums.CourseLevel;
import com.eduplatform.eduplatform_backend.common.enums.CourseType;
import com.eduplatform.eduplatform_backend.common.security.AuthenticatedPrincipal;
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
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
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
    @Operation(summary = "Course detail by slug",
            description = "Published courses, and archived courses that were published before being "
                    + "archived, are served to anyone; archived ones are left out of the catalogue but "
                    + "stay reachable here for the students enrolled in them. A DRAFT, IN_REVIEW or "
                    + "REJECTED course, or an archived one that was never published, is served only when "
                    + "the request carries a bearer token belonging to one of the course's tutors, to "
                    + "an admin, or to a participant holding an ACTIVE or COMPLETED enrolment, and is "
                    + "otherwise a 404 COURSE_NOT_FOUND, the same as a slug that does not exist. "
                    + "Every caller gets the full outline, but a lesson's description, videoUrl and "
                    + "videoMediaId only for preview lessons unless the token belongs to a tutor, an "
                    + "admin or such a participant; rejectionReason is only for tutors and admins.",
            security = {})
    public ApiResponse<CourseDto> bySlug(@PathVariable String slug) {
        CourseService.SlugView view = service.getBySlug(slug, optionalCaller());
        CourseDto dto = mapper.toDto(view.course());
        // The lessons' content is what a participant is given, and it used to be handed to every
        // anonymous caller in full — meeting links, texts and file ids of paid courses included.
        if (!view.fullLessonContent()) dto = dto.withLessonContentHidden();
        if (!view.moderationNote()) dto = dto.withoutModerationNote();
        return ApiResponse.ok(dto);
    }

    /**
     * The caller, if the request carried a valid access token; null for an anonymous one.
     *
     * <p>Not {@code @CurrentUser}, which rejects anonymous requests — this route is permitAll
     * and must keep serving the public site. JwtAuthFilter still runs on permitAll routes: a
     * valid token populates the SecurityContext, no token leaves it empty, and an invalid or
     * expired one is answered with a 401 before this method is reached, which the dashboard
     * already handles by refreshing and retrying.
     */
    private static AuthenticatedPrincipal optionalCaller() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null && auth.getPrincipal() instanceof AuthenticatedPrincipal principal ? principal : null;
    }
}
