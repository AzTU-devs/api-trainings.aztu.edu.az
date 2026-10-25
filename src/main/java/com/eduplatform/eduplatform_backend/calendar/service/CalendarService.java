package com.eduplatform.eduplatform_backend.calendar.service;

import com.eduplatform.eduplatform_backend.calendar.repo.CalendarQueries;
import com.eduplatform.eduplatform_backend.calendar.repo.CalendarQueries.BookingRow;
import com.eduplatform.eduplatform_backend.calendar.repo.CalendarQueries.CourseRow;
import com.eduplatform.eduplatform_backend.calendar.repo.CalendarQueries.SessionRow;
import com.eduplatform.eduplatform_backend.calendar.web.dto.CalendarDto;
import com.eduplatform.eduplatform_backend.calendar.web.dto.CalendarEventDto;
import com.eduplatform.eduplatform_backend.calendar.web.dto.CalendarEventDto.Kind;
import com.eduplatform.eduplatform_backend.common.enums.BookingStatus;
import com.eduplatform.eduplatform_backend.common.enums.CourseType;
import com.eduplatform.eduplatform_backend.common.error.Errors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The super admin's calendar: every in-person training, session and room booking over a window of
 * local days, ready to lay out like a day/week/month planner.
 *
 * <p>Where an event comes from:
 * <ol>
 *   <li>a ONE_TIME course is one timed event on its day ({@code one-time:<courseId>});</li>
 *   <li>an OFFLINE course is its sessions ({@code session:<id>}); without any, its approved room
 *       bookings ({@code booking:<id>}, still kind SESSION); with neither, one all-day event over its
 *       dates ({@code course:<courseId>});</li>
 *   <li>an approved or pending room booking that belongs to no course is a ROOM_BOOKING.</li>
 * </ol>
 * ONLINE courses have no schedule and never appear; nor does anything of a deleted or ARCHIVED one.
 */
@Service
public class CalendarService {

    /** About two months: the dashboard's month view with the days of its neighbours on show. */
    static final int MAX_DAYS = 62;

    /** Enough for a busy month many times over, and a bound on what one request can make us read. */
    static final int MAX_EVENTS = 2000;

    private static final DateTimeFormatter HH_MM = DateTimeFormatter.ofPattern("HH:mm");

    private static final Comparator<CalendarEventDto> BY_START = Comparator
            .comparing(CalendarEventDto::start)
            .thenComparing(CalendarEventDto::end)
            .thenComparing(CalendarEventDto::id);

    private final CalendarQueries queries;
    private final ZoneId zone;

    public CalendarService(CalendarQueries queries, @Value("${app.timezone:Asia/Baku}") String zone) {
        this.queries = queries;
        this.zone = ZoneId.of(zone);
    }

    /**
     * @param from the first local day shown
     * @param to   the day after the last one shown (exclusive), so a month is [1st, 1st of next)
     */
    @Transactional(readOnly = true)
    public CalendarDto calendar(LocalDate from, LocalDate to) {
        if (!from.isBefore(to)) {
            throw Errors.badRequest("INVALID_DATE_RANGE", "'from' must be before 'to'");
        }
        if (ChronoUnit.DAYS.between(from, to) > MAX_DAYS) {
            throw Errors.badRequest("RANGE_TOO_LONG", "The calendar shows at most " + MAX_DAYS + " days at a time");
        }
        Instant windowStart = midnight(from);
        Instant windowEnd = midnight(to);
        // One more than the cap from each source: each list comes sorted by start, so the first
        // MAX_EVENTS of the merged lists are the first of the window, and an extra row anywhere
        // is what tells a full window from a truncated one.
        int limit = MAX_EVENTS + 1;
        List<CourseRow> courses = queries.courseEvents(from, to, limit);
        List<SessionRow> sessions = queries.sessions(windowStart, windowEnd, limit);
        List<BookingRow> courseBookings = queries.courseBookings(windowStart, windowEnd, limit);
        List<BookingRow> standalone = queries.standaloneBookings(windowStart, windowEnd, limit);

        Set<UUID> courseIds = new HashSet<>();
        courses.forEach(c -> courseIds.add(c.id()));
        sessions.forEach(s -> courseIds.add(s.course().id()));
        courseBookings.forEach(b -> courseIds.add(b.course().id()));
        Map<UUID, List<String>> tutors = queries.tutorNames(courseIds);

        List<CalendarEventDto> events = new ArrayList<>(
                courses.size() + sessions.size() + courseBookings.size() + standalone.size());
        for (CourseRow c : courses) {
            events.add(c.type() == CourseType.ONE_TIME ? oneTime(c, tutors) : wholeCourse(c, tutors));
        }
        for (SessionRow s : sessions) {
            String title = s.topic() == null || s.topic().isBlank()
                    ? s.course().title() : s.course().title() + " · " + s.topic().trim();
            events.add(courseEvent("session:" + s.id(), Kind.SESSION, title, s.startsAt(), s.endsAt(), false,
                    s.course(), s.roomName(), s.bookingStatus(), tutors));
        }
        for (BookingRow b : courseBookings) {
            events.add(courseEvent("booking:" + b.id(), Kind.SESSION, b.course().title(), b.startsAt(), b.endsAt(),
                    false, b.course(), b.roomName(), b.status(), tutors));
        }
        for (BookingRow b : standalone) {
            events.add(roomBooking(b));
        }

        events.sort(BY_START);
        boolean truncated = events.size() > MAX_EVENTS;
        return new CalendarDto(zone.getId(), truncated ? List.copyOf(events.subList(0, MAX_EVENTS)) : events,
                truncated);
    }

    /** Its day between its two times; a row without both (none the API writes) is all day instead. */
    private CalendarEventDto oneTime(CourseRow c, Map<UUID, List<String>> tutors) {
        String id = "one-time:" + c.id();
        if (c.startTime() == null || c.endTime() == null) {
            return courseEvent(id, Kind.ONE_TIME, c.title(), midnight(c.startDate()),
                    midnight(c.startDate().plusDays(1)), true, c, null, null, tutors);
        }
        return courseEvent(id, Kind.ONE_TIME, c.title(), at(c.startDate(), c.startTime()),
                at(c.startDate(), c.endTime()), false, c, null, null, tutors);
    }

    /**
     * All day over its dates, even when it records daily hours: without sessions nothing says which
     * of those days it meets on, so the hours go in the title rather than onto every day.
     */
    private CalendarEventDto wholeCourse(CourseRow c, Map<UUID, List<String>> tutors) {
        String title = c.startTime() != null && c.endTime() != null
                ? c.title() + " (" + HH_MM.format(c.startTime()) + "–" + HH_MM.format(c.endTime()) + ")"
                : c.title();
        return courseEvent("course:" + c.id(), Kind.COURSE, title, midnight(c.startDate()),
                midnight(c.endDate().plusDays(1)), true, c, null, null, tutors);
    }

    private static CalendarEventDto courseEvent(String id, Kind kind, String title, Instant start, Instant end,
                                                boolean allDay, CourseRow c, String roomName,
                                                BookingStatus bookingStatus, Map<UUID, List<String>> tutors) {
        return new CalendarEventDto(id, kind, title, start, end, allDay,
                c.id(), c.slug(), c.type(), c.status(), roomName, c.city(), c.addressLine(),
                tutors.getOrDefault(c.id(), List.of()), c.seatLimit(), c.enrolledCount(), bookingStatus);
    }

    /** Titled by the room and who asked for it, "Room 301 · Leyla H.", since there is no course name. */
    private static CalendarEventDto roomBooking(BookingRow b) {
        String first = b.tutorFirstName() == null ? "" : b.tutorFirstName().trim();
        String last = b.tutorLastName() == null ? "" : b.tutorLastName().trim();
        String shortName = last.isEmpty() ? first : (first + " " + last.charAt(0) + ".").trim();
        String fullName = (first + " " + last).trim();
        String room = b.roomName() == null ? "Room" : b.roomName();
        String title = shortName.isEmpty() ? room : room + " · " + shortName;
        return new CalendarEventDto("booking:" + b.id(), Kind.ROOM_BOOKING, title, b.startsAt(), b.endsAt(), false,
                null, null, null, null, b.roomName(), null, null,
                fullName.isEmpty() ? List.of() : List.of(fullName), null, null, b.status());
    }

    private Instant midnight(LocalDate day) {
        return day.atStartOfDay(zone).toInstant();
    }

    private Instant at(LocalDate day, LocalTime time) {
        return day.atTime(time).atZone(zone).toInstant();
    }
}
