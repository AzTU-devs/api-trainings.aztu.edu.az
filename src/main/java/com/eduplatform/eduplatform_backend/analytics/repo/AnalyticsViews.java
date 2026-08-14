package com.eduplatform.eduplatform_backend.analytics.repo;

import java.util.UUID;

/** Read-only projections used to assemble the admin analytics overview. */
public final class AnalyticsViews {

    private AnalyticsViews() {}

    public interface TopCourseView {
        UUID getId();
        String getTitle();
        int getEnrolledCount();
    }

    public interface TopCategoryView {
        UUID getId();
        String getName();
        long getCourseCount();
    }

    /** One day of the enrollment trend ({@code day} = yyyy-MM-dd). */
    public interface TrendPointView {
        String getDay();
        long getCnt();
    }

    public interface RoomUtilizationView {
        UUID getRoomId();
        String getRoomName();
        double getBookedHours();
    }
}
