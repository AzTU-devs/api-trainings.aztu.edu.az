package com.eduplatform.eduplatform_backend.enrollment.repo;

import java.time.Instant;
import java.util.UUID;

/**
 * Aggregated read-projection of a single student across all of a tutor's courses.
 * Mapped from {@link EnrollmentRepository#findTutorStudents} by alias.
 */
public interface TutorStudentRow {
    UUID getUserId();
    String getFirstName();
    String getLastName();
    String getEmail();
    UUID getAvatarId();
    long getActiveEnrollments();
    long getTotalEnrollments();
    Double getAverageProgressPct();
    Instant getLastActivityAt();
}
