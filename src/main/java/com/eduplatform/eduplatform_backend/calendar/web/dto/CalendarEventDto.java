package com.eduplatform.eduplatform_backend.calendar.web.dto;

import com.eduplatform.eduplatform_backend.common.enums.BookingStatus;
import com.eduplatform.eduplatform_backend.common.enums.CourseStatus;
import com.eduplatform.eduplatform_backend.common.enums.CourseType;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * One entry on the super admin's calendar. The dashboard opens a course event's details from
 * {@code GET /api/admin/courses/{courseId}} and its participants list, so an event carries only
 * what the calendar cell and its popover show.
 *
 * @param id            stable across requests and unique within a response: {@code one-time:<courseId>},
 *                      {@code course:<courseId>}, {@code session:<sessionId>} or {@code booking:<bookingId>}
 *                      (a SESSION taken from a course's room booking keeps the booking's id)
 * @param start         for an all-day event, local midnight of its first day
 * @param end           exclusive; for an all-day event, local midnight after its last day
 * @param tutorNames    the course's teaching roster, the authorised editor first; for a booking
 *                      without a course, the expert who asked for the room. Never null.
 * @param seatLimit     the course's seats; null for a booking without a course
 * @param enrolledCount the places held on the course; null for a booking without a course
 * @param bookingStatus the room booking behind the event, when there is one
 */
public record CalendarEventDto(
        String id,
        Kind kind,
        String title,
        Instant start,
        Instant end,
        boolean allDay,
        UUID courseId,
        String courseSlug,
        CourseType courseType,
        CourseStatus courseStatus,
        String roomName,
        String city,
        String addressLine,
        List<String> tutorNames,
        Integer seatLimit,
        Integer enrolledCount,
        BookingStatus bookingStatus
) {

    public enum Kind {
        /** A ONE_TIME course: its one day between its two times. */
        ONE_TIME,
        /** An OFFLINE course with no sessions and no approved booking: all day over its dates. */
        COURSE,
        /** One meeting of an OFFLINE course: an offline session, or else an approved room booking. */
        SESSION,
        /** A room booking, approved or pending, that belongs to no course. */
        ROOM_BOOKING
    }
}
