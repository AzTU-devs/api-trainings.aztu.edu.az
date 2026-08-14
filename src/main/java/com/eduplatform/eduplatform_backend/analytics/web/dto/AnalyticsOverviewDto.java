package com.eduplatform.eduplatform_backend.analytics.web.dto;

import java.util.List;
import java.util.UUID;

/** Admin analytics overview — mirrors the dashboard's {@code AnalyticsOverview} contract. */
public record AnalyticsOverviewDto(
        long activeStudents,
        long activeTutors,
        long publishedCourses,
        long enrollmentsThisMonth,
        List<TrendPoint> enrollmentsTrend,
        List<TopCourse> topCourses,
        List<TopCategory> topCategories,
        List<RoomUtil> roomUtilization
) {
    public record TrendPoint(String date, long count) {}

    public record TopCourse(UUID id, String title, long enrolledCount) {}

    public record TopCategory(UUID id, String name, long courseCount) {}

    public record RoomUtil(UUID roomId, String roomName, double utilizationPct) {}
}
