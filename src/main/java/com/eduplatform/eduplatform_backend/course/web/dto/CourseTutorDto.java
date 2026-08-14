package com.eduplatform.eduplatform_backend.course.web.dto;

import java.util.UUID;

/**
 * One tutor on a course's teaching roster.
 *
 * @param tutorId     tutor profile id
 * @param displayName tutor's full name, for display on course pages
 * @param authorized  true for the single tutor allowed to edit this course
 *                    (the course itself belongs to the university)
 */
public record CourseTutorDto(
        UUID tutorId,
        String displayName,
        boolean authorized
) {}
