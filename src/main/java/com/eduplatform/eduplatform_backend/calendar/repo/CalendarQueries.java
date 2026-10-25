package com.eduplatform.eduplatform_backend.calendar.repo;

import com.eduplatform.eduplatform_backend.common.enums.BookingStatus;
import com.eduplatform.eduplatform_backend.common.enums.CourseStatus;
import com.eduplatform.eduplatform_backend.common.enums.CourseType;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The SQL behind the super admin's calendar (CalendarService): one statement per kind of event, one
 * for the tutors' names and one for the courses' daily times, so a window costs six queries however
 * many trainings it holds.
 *
 * <p>Plain SQL rather than the entities: an event needs a dozen columns from four or five tables,
 * which through the entities would mean walking lazy graphs row by row. Each statement says for
 * itself which rows count: a course that is deleted or ARCHIVED is never shown, and neither is
 * anything hanging off it. Only columns that exist since V22 are read, so booking series (V23) show
 * up as the ordinary booking rows they are made of.
 *
 * <p>Every event statement is ordered by start and limited by the caller, so that taking the first
 * N events of the merged lists is the same as taking the first N of the whole window.
 */
@Repository
public class CalendarQueries {

    /**
     * What a course event shows of its course; every statement below aliases courses c and details d.
     * Not the daily times, which are read apart: see {@link #dailyTimes}.
     */
    private static final String COURSE_COLUMNS = """
            c.id as course_id, c.slug, c.title, c.course_type, c.status as course_status, c.enrolled_count,
            d.start_date, d.end_date, d.student_limit, d.city, d.address_line""";

    private static final String SHOWN_COURSE = "c.deleted_at is null and c.status <> 'ARCHIVED'";

    /**
     * Whether an OFFLINE course is scheduled by its sessions. Any session counts, a cancelled one
     * too: cancelling a meeting does not hand the schedule over to the bookings, which may still
     * stand for the very meetings that were called off.
     */
    private static final String HAS_SESSIONS =
            "exists (select 1 from offline_sessions s0 where s0.offline_course_id = c.id)";

    private static final String HAS_APPROVED_BOOKING = """
            exists (select 1 from room_bookings b0
                     where b0.offline_course_id = c.id and b0.status = 'APPROVED' and b0.deleted_at is null)""";

    private final NamedParameterJdbcTemplate jdbc;

    @PersistenceContext
    private EntityManager entityManager;

    public CalendarQueries(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** The daily times are filled in on the rows of {@link #courseEvents} only, the one place they are shown. */
    public record CourseRow(UUID id, String slug, String title, CourseType type, CourseStatus status,
                            int enrolledCount, LocalDate startDate, LocalDate endDate,
                            LocalTime startTime, LocalTime endTime, int seatLimit,
                            String city, String addressLine) {

        CourseRow withTimes(LocalTime start, LocalTime end) {
            return new CourseRow(id, slug, title, type, status, enrolledCount, startDate, endDate, start, end,
                    seatLimit, city, addressLine);
        }
    }

    /** An offline session, with the room of the booking it is held under, if any. */
    public record SessionRow(UUID id, Instant startsAt, Instant endsAt, String topic,
                             String roomName, BookingStatus bookingStatus, CourseRow course) {}

    /**
     * A room booking. {@code course} is set for a course's booking and null for one that belongs
     * to no course, which carries its expert's name instead.
     */
    public record BookingRow(UUID id, Instant startsAt, Instant endsAt, BookingStatus status, String roomName,
                             String tutorFirstName, String tutorLastName, CourseRow course) {}

    /**
     * The courses that are an event by themselves, whose dates meet [from, to): every ONE_TIME
     * course, and the OFFLINE ones with neither sessions nor an approved booking to schedule them.
     * The one day of a one-time training lies within its date, so comparing dates is exact. Ordered
     * by day alone; the service orders a day's events by their times.
     */
    public List<CourseRow> courseEvents(LocalDate from, LocalDate to, int limit) {
        List<CourseRow> rows = jdbc.query("select " + COURSE_COLUMNS + """

                  from courses c
                  join offline_course_details d on d.course_id = c.id
                 where %s
                   and d.start_date < :to and d.end_date >= :from
                   and (c.course_type = 'ONE_TIME'
                        or (c.course_type = 'OFFLINE' and not %s and not %s))
                 order by d.start_date, c.id
                 limit :limit
                """.formatted(SHOWN_COURSE, HAS_SESSIONS, HAS_APPROVED_BOOKING),
                new MapSqlParameterSource().addValue("from", from).addValue("to", to).addValue("limit", limit),
                (rs, i) -> course(rs));
        if (rows.isEmpty()) return rows;
        Map<UUID, LocalTime[]> times = dailyTimes(rows.stream().map(CourseRow::id).toList());
        return rows.stream().map(r -> {
            LocalTime[] t = times.getOrDefault(r.id(), new LocalTime[2]);
            return r.withTimes(t[0], t[1]);
        }).toList();
    }

    /**
     * The daily start and end times, read through the entity rather than with SQL. Hibernate binds a
     * LocalTime through hibernate.jdbc.time_zone (UTC), so wherever the JVM's own zone is not UTC the
     * column holds the time shifted by that zone's offset (10:00 is stored as 06:00 on a JVM in Baku);
     * only reading it back the same way gives the times the course page shows.
     */
    private Map<UUID, LocalTime[]> dailyTimes(Collection<UUID> courseIds) {
        Map<UUID, LocalTime[]> out = new HashMap<>();
        entityManager.createQuery(
                        "select d.courseId, d.startTime, d.endTime from OfflineCourseDetails d where d.courseId in :ids",
                        Object[].class)
                .setParameter("ids", courseIds)
                .getResultList()
                .forEach(row -> out.put((UUID) row[0], new LocalTime[]{(LocalTime) row[1], (LocalTime) row[2]}));
        return out;
    }

    /** The OFFLINE courses' sessions that overlap [from, to); a cancelled one is not taking place. */
    public List<SessionRow> sessions(Instant from, Instant to, int limit) {
        return jdbc.query("""
                select s.id, s.starts_at, s.ends_at, s.topic, r.name as room_name, b.status as booking_status,
                """ + COURSE_COLUMNS + """

                  from offline_sessions s
                  join courses c on c.id = s.offline_course_id
                  join offline_course_details d on d.course_id = c.id
                  left join room_bookings b on b.id = s.booking_id and b.deleted_at is null
                  left join rooms r on r.id = b.room_id
                 where %s and c.course_type = 'OFFLINE'
                   and s.status <> 'CANCELLED'
                   and s.starts_at < :to and s.ends_at > :from
                 order by s.starts_at, s.ends_at, s.id
                 limit :limit
                """.formatted(SHOWN_COURSE),
                window(from, to, limit),
                (rs, i) -> new SessionRow(uuid(rs, "id"), instant(rs, "starts_at"), instant(rs, "ends_at"),
                        rs.getString("topic"), rs.getString("room_name"),
                        bookingStatus(rs.getString("booking_status")), course(rs)));
    }

    /**
     * The approved bookings overlapping [from, to) of the OFFLINE courses that have no sessions,
     * whose meetings they then are. A room is read whether or not it was deleted since.
     */
    public List<BookingRow> courseBookings(Instant from, Instant to, int limit) {
        return jdbc.query("""
                select b.id, b.starts_at, b.ends_at, b.status as booking_status, r.name as room_name,
                """ + COURSE_COLUMNS + """

                  from room_bookings b
                  join courses c on c.id = b.offline_course_id
                  join offline_course_details d on d.course_id = c.id
                  left join rooms r on r.id = b.room_id
                 where b.deleted_at is null and b.status = 'APPROVED'
                   and %s and c.course_type = 'OFFLINE' and not %s
                   and b.starts_at < :to and b.ends_at > :from
                 order by b.starts_at, b.ends_at, b.id
                 limit :limit
                """.formatted(SHOWN_COURSE, HAS_SESSIONS),
                window(from, to, limit),
                (rs, i) -> new BookingRow(uuid(rs, "id"), instant(rs, "starts_at"), instant(rs, "ends_at"),
                        bookingStatus(rs.getString("booking_status")), rs.getString("room_name"),
                        null, null, course(rs)));
    }

    /**
     * The approved and pending bookings overlapping [from, to) that belong to no course, the
     * occurrences of a booking series included, with the name of the expert who asked.
     */
    public List<BookingRow> standaloneBookings(Instant from, Instant to, int limit) {
        return jdbc.query("""
                select b.id, b.starts_at, b.ends_at, b.status as booking_status, r.name as room_name,
                       u.first_name, u.last_name
                  from room_bookings b
                  left join rooms r on r.id = b.room_id
                  left join tutor_profiles t on t.id = b.tutor_id
                  left join users u on u.id = t.user_id
                 where b.deleted_at is null and b.offline_course_id is null
                   and b.status in ('APPROVED', 'PENDING')
                   and b.starts_at < :to and b.ends_at > :from
                 order by b.starts_at, b.ends_at, b.id
                 limit :limit
                """,
                window(from, to, limit),
                (rs, i) -> new BookingRow(uuid(rs, "id"), instant(rs, "starts_at"), instant(rs, "ends_at"),
                        bookingStatus(rs.getString("booking_status")), rs.getString("room_name"),
                        rs.getString("first_name"), rs.getString("last_name"), null));
    }

    /**
     * Each course's teaching roster by name, the authorised editor first, as the course page lists
     * it: an expert profile retired with its account (V21) is no longer on it.
     */
    public Map<UUID, List<String>> tutorNames(Collection<UUID> courseIds) {
        Map<UUID, List<String>> names = new LinkedHashMap<>();
        if (courseIds.isEmpty()) return names;
        jdbc.query("""
                select ct.course_id, trim(concat(u.first_name, ' ', u.last_name)) as name
                  from course_tutors ct
                  join courses c on c.id = ct.course_id
                  join tutor_profiles t on t.id = ct.tutor_id and t.deleted_at is null
                  join users u on u.id = t.user_id
                 where ct.course_id in (:ids)
                 order by ct.course_id, (t.id = c.tutor_id) desc, u.first_name, u.last_name, t.id
                """,
                new MapSqlParameterSource("ids", courseIds),
                rs -> {
                    names.computeIfAbsent(uuid(rs, "course_id"), id -> new ArrayList<>())
                            .add(rs.getString("name"));
                });
        return names;
    }

    // ── column readers ───────────────────────────────────────────────────

    private static MapSqlParameterSource window(Instant from, Instant to, int limit) {
        // As offsets: the driver binds an OffsetDateTime as timestamptz, and has no mapping for Instant.
        return new MapSqlParameterSource()
                .addValue("from", OffsetDateTime.ofInstant(from, ZoneOffset.UTC))
                .addValue("to", OffsetDateTime.ofInstant(to, ZoneOffset.UTC))
                .addValue("limit", limit);
    }

    private static CourseRow course(ResultSet rs) throws SQLException {
        return new CourseRow(
                uuid(rs, "course_id"), rs.getString("slug"), rs.getString("title"),
                CourseType.valueOf(rs.getString("course_type")), CourseStatus.valueOf(rs.getString("course_status")),
                rs.getInt("enrolled_count"),
                rs.getObject("start_date", LocalDate.class), rs.getObject("end_date", LocalDate.class),
                null, null,
                rs.getInt("student_limit"), rs.getString("city"), rs.getString("address_line"));
    }

    private static UUID uuid(ResultSet rs, String column) throws SQLException {
        return rs.getObject(column, UUID.class);
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    private static BookingStatus bookingStatus(String value) {
        return value == null ? null : BookingStatus.valueOf(value);
    }
}
