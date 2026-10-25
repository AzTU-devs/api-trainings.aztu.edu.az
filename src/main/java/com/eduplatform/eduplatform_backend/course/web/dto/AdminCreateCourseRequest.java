package com.eduplatform.eduplatform_backend.course.web.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Create a course on behalf of the university.
 *
 * <p>Unlike the tutor-facing request, the tutors are stated explicitly rather than
 * inferred from the caller — a super admin has no tutor profile of their own, and
 * the course belongs to the university regardless of who teaches it.
 *
 * @param course             the course fields themselves, identical to the tutor flow
 * @param tutorIds           full teaching roster; must contain at least one tutor
 * @param authorizedTutorId  the single tutor allowed to edit this course.
 *                           Must be one of {@code tutorIds}.
 * @param participantUserIds optional: accounts to seat on the course as it is created, each exactly as
 *                           the participants screen's "add participant" does (an ADMIN_GRANT place,
 *                           counted, past the seat limit if need be). A repeated id is seated once; an
 *                           unknown or deleted account is 400 USER_NOT_FOUND and nothing is created.
 *                           A list rather than a set so the limit bounds what is sent, repeats and all.
 */
public record AdminCreateCourseRequest(
        @NotNull @Valid CreateCourseRequest course,
        @NotEmpty(message = "At least one tutor must be assigned") Set<@NotNull UUID> tutorIds,
        @NotNull(message = "An authorised tutor must be nominated") UUID authorizedTutorId,
        @Size(max = 500, message = "At most 500 participants can be seated at once")
        List<@NotNull UUID> participantUserIds
) {}
