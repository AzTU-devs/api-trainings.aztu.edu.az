package com.eduplatform.eduplatform_backend.course.web.dto;

import com.eduplatform.eduplatform_backend.common.enums.CourseLevel;
import com.eduplatform.eduplatform_backend.common.enums.CourseStatus;
import com.eduplatform.eduplatform_backend.common.enums.CourseType;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Full course detail page. */
public record CourseDto(
        UUID id,
        String slug,
        String title,
        String subtitle,
        String description,
        String requirements,
        String learningOutcomes,
        /** Legacy free text; readers fall back to it only when {@code syllabusItems} is empty. */
        String syllabus,
        /** The syllabus in display order; always an array, empty when the course has none. */
        List<SyllabusItemDto> syllabusItems,
        UUID thumbnailMediaId,
        UUID trailerMediaId,
        CourseType courseType,
        CourseLevel level,
        String language,
        boolean free,
        BigDecimal price,
        String currency,
        CourseStatus status,
        Instant publishedAt,
        /** When the course was last submitted for review; null if it never was. */
        Instant submittedAt,
        /**
         * The moderator's note when the course was sent back. Only the course's tutors and staff
         * see it; the public endpoint clears it for everyone else.
         */
        String rejectionReason,
        BigDecimal ratingAvg,
        int ratingCount,
        int enrolledCount,
        UUID tutorId,
        String tutorDisplayName,
        /** Full teaching roster; tutorId above is the one authorised to edit. */
        List<CourseTutorDto> tutors,
        Set<UUID> categoryIds,
        Set<UUID> tagIds,
        OnlineDetailsDto onlineDetails,
        OfflineDetailsDto offlineDetails,
        List<ModuleDto> modules,
        /**
         * The optimistic-lock version. Send it back as {@code version} on PATCH and a save based on
         * an out-of-date copy is refused with 409 STALE_RESOURCE instead of overwriting whatever
         * someone else saved in between.
         */
        long version
) {

    /**
     * The course as a visitor who holds no place on it sees it: the full outline — every module
     * and lesson with its title, type, length and order — but the content itself (the text, the
     * meeting or video link, the file) only for preview lessons.
     */
    public CourseDto withLessonContentHidden() {
        List<ModuleDto> outline = modules == null ? null : modules.stream()
                .map(ModuleDto::withLessonContentHidden)
                .toList();
        return new CourseDto(id, slug, title, subtitle, description, requirements, learningOutcomes, syllabus,
                syllabusItems, thumbnailMediaId, trailerMediaId, courseType, level, language, free, price, currency,
                status, publishedAt, submittedAt, rejectionReason, ratingAvg, ratingCount, enrolledCount, tutorId,
                tutorDisplayName, tutors, categoryIds, tagIds, onlineDetails, offlineDetails, outline, version);
    }

    /** Without the moderator's note, which is for the course's tutors and staff only. */
    public CourseDto withoutModerationNote() {
        return new CourseDto(id, slug, title, subtitle, description, requirements, learningOutcomes, syllabus,
                syllabusItems, thumbnailMediaId, trailerMediaId, courseType, level, language, free, price, currency,
                status, publishedAt, submittedAt, null, ratingAvg, ratingCount, enrolledCount, tutorId,
                tutorDisplayName, tutors, categoryIds, tagIds, onlineDetails, offlineDetails, modules, version);
    }
}
