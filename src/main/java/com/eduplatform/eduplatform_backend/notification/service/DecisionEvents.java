package com.eduplatform.eduplatform_backend.notification.service;

import java.util.UUID;

/**
 * Moderation decisions that the person they concern has to hear about. Published by the tutor
 * and course services inside their transaction and delivered by {@link DecisionNotifier} once it
 * has committed, so nobody is told about a decision that then rolled back.
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
}
