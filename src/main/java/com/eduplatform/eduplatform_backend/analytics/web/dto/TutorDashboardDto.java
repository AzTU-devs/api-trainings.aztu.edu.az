package com.eduplatform.eduplatform_backend.analytics.web.dto;

/** Headline counters for the tutor dashboard landing page. */
public record TutorDashboardDto(
        long courses,
        long publishedCourses,
        long students,
        long coursesInReview,
        long approvedBookings
) {}
