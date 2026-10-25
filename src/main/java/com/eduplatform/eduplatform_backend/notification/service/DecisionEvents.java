package com.eduplatform.eduplatform_backend.notification.service;

import java.util.UUID;

/**
 * Moderation decisions that the person they concern has to hear about. Published by the tutor,
 * course and room booking services inside their transaction and delivered by
 * {@link DecisionNotifier} once it has committed, so nobody is told about a decision that then
 * rolled back.
 */
public final class DecisionEvents {

    private DecisionEvents() {}

    /**
     * An expert application was approved or rejected, or an approved expert's approval was
     * withdrawn ({@code revoked}).
     */
    public record TutorDecided(UUID userId, boolean approved, boolean revoked, String note) {}

    /** A course the tutor submitted was published or sent back, by review or directly. */
    public record CourseDecided(UUID ownerUserId, UUID courseId, String courseTitle,
                                boolean published, String note) {}

    /**
     * A room request was approved or rejected: a single booking ({@code seriesId} null) or a whole
     * series ({@code bookingId} null). {@code schedule} is the requested time in the platform's
     * zone, written with digits only ("2026-11-02 10:00–12:00"), so it reads the same in every
     * language.
     */
    public record RoomBookingDecided(UUID tutorUserId, UUID bookingId, UUID seriesId, String roomName,
                                     String schedule, boolean approved, String note) {}
}
