package com.eduplatform.eduplatform_backend.common.enums;

/**
 * How a training is delivered.
 *
 * <ul>
 *   <li>{@code ONLINE}: lessons on the platform.</li>
 *   <li>{@code OFFLINE}: in person, over a range of dates.</li>
 *   <li>{@code ONE_TIME}: in person, once: a single date with a start and an end time. Its schedule
 *       is kept in the same offline details row an OFFLINE course uses.</li>
 * </ul>
 */
public enum CourseType {

    ONLINE, OFFLINE, ONE_TIME;

    /**
     * Whether the training takes place in a room: it has an offline details row, a seat limit that
     * enrolment claims against, and the room bookings, sessions and attendance hanging off that
     * row. Every in-person rule asks this rather than comparing with OFFLINE, which is how a
     * one-time training gets the same treatment everywhere without each check naming it.
     */
    public boolean isInPerson() {
        return this == OFFLINE || this == ONE_TIME;
    }
}
