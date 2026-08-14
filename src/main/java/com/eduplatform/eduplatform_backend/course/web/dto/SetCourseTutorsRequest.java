package com.eduplatform.eduplatform_backend.course.web.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.util.Set;
import java.util.UUID;

/**
 * Replace a course's teaching roster and nominate which tutor may edit it.
 *
 * <p>This is a full replacement, not a merge: tutors absent from {@code tutorIds}
 * are removed from the course.
 *
 * @param tutorIds          the complete roster; must contain at least one tutor
 * @param authorizedTutorId the single tutor allowed to edit. Must be one of
 *                          {@code tutorIds}, so a course can never end up with an
 *                          editor who does not teach it.
 */
public record SetCourseTutorsRequest(
        @NotEmpty(message = "A course must have at least one tutor") Set<UUID> tutorIds,
        @NotNull(message = "An authorised tutor must be nominated") UUID authorizedTutorId
) {}
