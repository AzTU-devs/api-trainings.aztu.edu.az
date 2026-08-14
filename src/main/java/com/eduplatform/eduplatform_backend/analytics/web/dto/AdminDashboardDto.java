package com.eduplatform.eduplatform_backend.analytics.web.dto;

/** Headline counters for the admin/staff dashboard landing page. */
public record AdminDashboardDto(
        long totalUsers,
        long pendingTutorApprovals,
        long pendingCourseReviews,
        long publishedCourses,
        long totalRooms,
        long pendingRoomRequests,
        long totalEnrollments
) {}
