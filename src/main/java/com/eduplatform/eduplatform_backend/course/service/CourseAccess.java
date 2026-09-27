package com.eduplatform.eduplatform_backend.course.service;

import com.eduplatform.eduplatform_backend.common.enums.EnrollmentStatus;
import com.eduplatform.eduplatform_backend.common.enums.RoleCode;
import com.eduplatform.eduplatform_backend.common.security.AuthenticatedPrincipal;
import com.eduplatform.eduplatform_backend.course.domain.Course;
import com.eduplatform.eduplatform_backend.enrollment.repo.EnrollmentRepository;
import org.springframework.stereotype.Component;

import java.util.EnumSet;
import java.util.Set;
import java.util.UUID;

/**
 * Who may see what of a course. One place, because the same questions were answered separately
 * — and differently — by the public course page, the tutor's content editor and the media
 * endpoint, and each gap was a leak or a lock-out of its own:
 *
 * <ul>
 *   <li>{@link #teaches}: the authorised editor or anyone on the teaching roster.</li>
 *   <li>{@link #mayManageContent}: staff, or someone who teaches the course — the audience of the
 *       dashboard's module and lesson editor, drafts included.</li>
 *   <li>{@link #hasFullAccess}: that audience plus the participants who hold a place — who may read
 *       every lesson, and open the course even once it is unpublished.</li>
 * </ul>
 *
 * <p>A place is an enrolment that is ACTIVE or COMPLETED ({@link #PLACE_HOLDING}). A cancelled or
 * refunded one is a place that was taken away, and grants nothing: removing a participant used to
 * revoke nothing at all, because every check asked only whether an enrolment row existed.
 */
@Component
public class CourseAccess {

    /** The enrolment statuses that occupy a place on a course and are counted in enrolledCount. */
    public static final Set<EnrollmentStatus> PLACE_HOLDING =
            EnumSet.of(EnrollmentStatus.ACTIVE, EnrollmentStatus.COMPLETED);

    private final EnrollmentRepository enrollments;

    public CourseAccess(EnrollmentRepository enrollments) {
        this.enrollments = enrollments;
    }

    public static boolean holdsAPlace(EnrollmentStatus status) {
        return status != null && PLACE_HOLDING.contains(status);
    }

    public static boolean isStaff(AuthenticatedPrincipal caller) {
        return caller != null && (caller.roles().contains(RoleCode.ADMIN.name())
                || caller.roles().contains(RoleCode.SUPER_ADMIN.name()));
    }

    /**
     * The authorised editor or anyone on the roster. The editor is checked as well as the roster:
     * they are meant to be on it, but a roster that drifted must not lock them out of their own
     * course. Reads the tutors' users, so they must be loaded (or loadable) by the caller.
     */
    public static boolean teaches(Course course, UUID userId) {
        if (userId == null) return false;
        if (course.getTutor() != null && userId.equals(course.getTutor().getUser().getId())) return true;
        return course.getTutors().stream()
                .anyMatch(t -> t.getUser() != null && userId.equals(t.getUser().getId()));
    }

    public static boolean mayManageContent(Course course, AuthenticatedPrincipal caller) {
        return caller != null && (isStaff(caller) || teaches(course, caller.userId()));
    }

    /** Staff, a tutor of the course, or a participant who holds a place on it. */
    public boolean hasFullAccess(Course course, AuthenticatedPrincipal viewer) {
        if (viewer == null) return false;
        return mayManageContent(course, viewer)
                || enrollments.existsByUserIdAndCourseIdAndStatusIn(viewer.userId(), course.getId(), PLACE_HOLDING);
    }
}
