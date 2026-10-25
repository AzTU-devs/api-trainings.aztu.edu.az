package com.eduplatform.eduplatform_backend.calendar;

import com.eduplatform.eduplatform_backend.support.AbstractIntegrationTest;
import com.eduplatform.eduplatform_backend.support.ApiClient;
import com.eduplatform.eduplatform_backend.support.Json;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * GET /api/admin/calendar: the super admin's calendar, behind {@code calendar:view}, which V24 grants
 * to SUPER_ADMIN alone.
 *
 * <p>The database is shared with every other test class, so each test works in a window of its own,
 * centuries ahead where nothing else schedules anything, and only ever looks at the events it made.
 * Sessions and bookings are written with SQL: nothing in the API creates sessions, and a booking's
 * own rules are RoomBookingRulesTest's business.
 */
class CalendarAdminTest extends AbstractIntegrationTest {

    private static final ZoneId BAKU = ZoneId.of("Asia/Baku");

    // ── access and the window ──────────────────────────────────────────

    @Test
    void onlyASuperAdminSeesTheCalendar() {
        LocalDate from = LocalDate.of(2200, 1, 1);
        LocalDate to = from.plusMonths(1);

        calendar(newAdminToken(), from, to).expectError(403, "FORBIDDEN");
        calendar(login(newApprovedTutor("calendar-tutor").email()), from, to).expectError(403, "FORBIDDEN");
        calendar(login(newUser("calendar-user", "USER").email()), from, to).expectError(403, "FORBIDDEN");
        api.get("/api/admin/calendar").query("from", from).query("to", to).send().expectStatus(401);

        JsonNode data = calendar(superToken(), from, to).expectStatus(200).data();
        assertThat(data.path("timezone").asText()).isEqualTo("Asia/Baku");
        assertThat(data.path("events").isArray()).isTrue();
        assertThat(data.path("truncated").asBoolean(true)).isFalse();
    }

    @Test
    void theWindowIsCheckedBeforeAnythingIsRead() {
        String token = superToken();
        LocalDate from = LocalDate.of(2200, 2, 1);

        calendar(token, from, from).expectError(400, "INVALID_DATE_RANGE");
        calendar(token, from, from.minusDays(1)).expectError(400, "INVALID_DATE_RANGE");
        calendar(token, from, from.plusDays(63)).expectError(400, "RANGE_TOO_LONG");
        calendar(token, from, from.plusDays(62)).expectStatus(200);
        calendar(token, from, from.plusDays(1)).expectStatus(200);

        api.get("/api/admin/calendar").bearer(token).query("from", from).send()
                .expectError(400, "MISSING_PARAMETER");
        api.get("/api/admin/calendar").bearer(token).query("from", "01.02.2200").query("to", "2200-03-01").send()
                .expectError(400, "INVALID_PARAMETER");
    }

    // ── where events come from ─────────────────────────────────────────

    @Test
    void aOneTimeCourseIsOneTimedEventWithItsRosterAndSeats() {
        LocalDate day = LocalDate.of(2201, 3, 14);
        TestTutor editor = newApprovedTutor("Zeynalov");
        TestTutor second = newApprovedTutor("Aliyeva");
        Map<String, Object> request = oneTimeRequest(day, "10:00", "13:30", 25);
        String title = (String) request.get("title");
        TestUser first = newUser("participant", "USER");
        TestUser other = newUser("participant", "USER");
        JsonNode created = api.post("/api/admin/courses").bearer(newAdminToken())
                .json(Json.object("course", request,
                        "tutorIds", List.of(editor.profileId(), second.profileId()),
                        "authorizedTutorId", editor.profileId(),
                        "participantUserIds", List.of(first.id(), other.id())))
                .send().expectStatus(201).data();
        UUID id = UUID.fromString(created.path("id").asText());

        JsonNode event = event(calendar(superToken(), day.withDayOfMonth(1), day.withDayOfMonth(1).plusMonths(1))
                .expectStatus(200).data(), "one-time:" + id);

        assertThat(event.path("kind").asText()).isEqualTo("ONE_TIME");
        assertThat(event.path("title").asText()).isEqualTo(title);
        assertThat(instant(event, "start")).isEqualTo(at(day, 10, 0));
        assertThat(instant(event, "end")).isEqualTo(at(day, 13, 30));
        assertThat(event.path("allDay").asBoolean(true)).isFalse();
        assertThat(event.path("courseId").asText()).isEqualTo(id.toString());
        assertThat(event.path("courseSlug").asText()).isEqualTo(created.path("slug").asText());
        assertThat(event.path("courseType").asText()).isEqualTo("ONE_TIME");
        assertThat(event.path("courseStatus").asText()).as("a draft is on the calendar too").isEqualTo("DRAFT");
        assertThat(event.path("city").asText()).isEqualTo("Baku");
        assertThat(event.path("addressLine").asText()).isEqualTo("AzTU, 20 H. Javid Ave");
        assertThat(event.path("roomName").isNull()).isTrue();
        assertThat(Json.texts(event.path("tutorNames")))
                .as("the authorised editor first").containsExactly("Test Zeynalov", "Test Aliyeva");
        assertThat(event.path("seatLimit").asInt()).isEqualTo(25);
        assertThat(event.path("enrolledCount").asInt()).isEqualTo(2);
        assertThat(event.path("bookingStatus").isNull()).isTrue();
    }

    @Test
    void anOfflineCourseIsItsSessionsAndNothingElse() {
        LocalDate from = LocalDate.of(2202, 3, 1);
        TestTutor tutor = newApprovedTutor("Mammadov");
        Map<String, Object> request = offlineRequest(from, from.plusDays(30), "10:00", "12:00");
        String title = (String) request.get("title");
        UUID course = createAsAdmin(tutor, request);
        UUID room = room("Room " + word());
        String roomName = roomName(room);
        UUID booked = booking(room, course, tutor, at(from.plusDays(2), 10, 0), at(from.plusDays(2), 12, 0), "APPROVED");
        UUID intro = session(course, at(from.plusDays(2), 10, 0), at(from.plusDays(2), 12, 0), "Intro", booked, "SCHEDULED");
        UUID plain = session(course, at(from.plusDays(9), 10, 0), at(from.plusDays(9), 12, 0), null, null, "HELD");
        UUID cancelled = session(course, at(from.plusDays(16), 10, 0), at(from.plusDays(16), 12, 0), null, null, "CANCELLED");
        UUID later = session(course, at(from.plusDays(40), 10, 0), at(from.plusDays(40), 12, 0), null, null, "SCHEDULED");
        // An approved booking of a course with sessions is not a second copy of its meetings.
        booking(room, course, tutor, at(from.plusDays(5), 10, 0), at(from.plusDays(5), 12, 0), "APPROVED");

        JsonNode data = calendar(superToken(), from, from.plusMonths(1)).expectStatus(200).data();

        assertThat(idsOfCourse(data, course)).containsExactly("session:" + intro, "session:" + plain);
        JsonNode first = event(data, "session:" + intro);
        assertThat(first.path("kind").asText()).isEqualTo("SESSION");
        assertThat(first.path("title").asText()).isEqualTo(title + " · Intro");
        assertThat(instant(first, "start")).isEqualTo(at(from.plusDays(2), 10, 0));
        assertThat(instant(first, "end")).isEqualTo(at(from.plusDays(2), 12, 0));
        assertThat(first.path("allDay").asBoolean(true)).isFalse();
        assertThat(first.path("roomName").asText()).isEqualTo(roomName);
        assertThat(first.path("bookingStatus").asText()).isEqualTo("APPROVED");
        assertThat(first.path("courseType").asText()).isEqualTo("OFFLINE");
        assertThat(Json.texts(first.path("tutorNames"))).containsExactly("Test Mammadov");
        assertThat(first.path("seatLimit").asInt()).isEqualTo(30);
        assertThat(first.path("enrolledCount").asInt()).isZero();
        JsonNode second = event(data, "session:" + plain);
        assertThat(second.path("title").asText()).isEqualTo(title);
        assertThat(second.path("roomName").isNull()).isTrue();
        assertThat(second.path("bookingStatus").isNull()).isTrue();
        assertThat(ids(data)).doesNotContain("session:" + cancelled, "session:" + later, "course:" + course);
    }

    @Test
    void anOfflineCourseWithoutSessionsIsItsApprovedBookings() {
        LocalDate from = LocalDate.of(2203, 3, 1);
        TestTutor tutor = newApprovedTutor("Hasanov");
        Map<String, Object> request = offlineRequest(from, from.plusDays(30), null, null);
        UUID course = createAsAdmin(tutor, request);
        UUID room = room("Room " + word());
        UUID approved = booking(room, course, tutor, at(from.plusDays(3), 14, 0), at(from.plusDays(3), 16, 0), "APPROVED");
        UUID pending = booking(room, course, tutor, at(from.plusDays(4), 14, 0), at(from.plusDays(4), 16, 0), "PENDING");

        JsonNode data = calendar(superToken(), from, from.plusMonths(1)).expectStatus(200).data();

        assertThat(idsOfCourse(data, course)).containsExactly("booking:" + approved);
        JsonNode event = event(data, "booking:" + approved);
        assertThat(event.path("kind").asText()).isEqualTo("SESSION");
        assertThat(event.path("title").asText()).isEqualTo(request.get("title"));
        assertThat(event.path("roomName").asText()).isEqualTo(roomName(room));
        assertThat(event.path("bookingStatus").asText()).isEqualTo("APPROVED");
        assertThat(instant(event, "start")).isEqualTo(at(from.plusDays(3), 14, 0));
        assertThat(ids(data)).as("a course's pending booking is not a meeting yet, nor a booking of nobody's")
                .doesNotContain("booking:" + pending);
    }

    @Test
    void anOfflineCourseWithNeitherIsOneAllDayEventOverItsDates() {
        LocalDate from = LocalDate.of(2204, 3, 1);
        LocalDate to = LocalDate.of(2204, 4, 1);
        TestTutor tutor = newApprovedTutor("Quliyev");
        Map<String, Object> spanning = offlineRequest(LocalDate.of(2204, 2, 25), LocalDate.of(2204, 3, 3), "10:00", "12:00");
        UUID spanningId = createAsAdmin(tutor, spanning);
        Map<String, Object> untimed = offlineRequest(LocalDate.of(2204, 3, 31), LocalDate.of(2204, 4, 20), null, null);
        UUID untimedId = createAsAdmin(tutor, untimed);
        UUID endedBefore = createAsAdmin(tutor, offlineRequest(LocalDate.of(2204, 2, 1), LocalDate.of(2204, 2, 29), null, null));
        UUID startsAfter = createAsAdmin(tutor, offlineRequest(to, to.plusDays(5), null, null));
        // Only an approved booking takes over the schedule; a pending one leaves the all-day event.
        Map<String, Object> pendingOnly = offlineRequest(LocalDate.of(2204, 3, 10), LocalDate.of(2204, 3, 12), null, null);
        UUID pendingOnlyId = createAsAdmin(tutor, pendingOnly);
        UUID pending = booking(room("Room " + word()), pendingOnlyId, tutor,
                at(LocalDate.of(2204, 3, 10), 9, 0), at(LocalDate.of(2204, 3, 10), 11, 0), "PENDING");

        JsonNode data = calendar(superToken(), from, to).expectStatus(200).data();

        JsonNode event = event(data, "course:" + spanningId);
        assertThat(event.path("kind").asText()).isEqualTo("COURSE");
        assertThat(event.path("allDay").asBoolean(false)).isTrue();
        assertThat(event.path("title").asText()).isEqualTo(spanning.get("title") + " (10:00–12:00)");
        assertThat(instant(event, "start")).as("local midnight of its first day, before the window")
                .isEqualTo(LocalDate.of(2204, 2, 25).atStartOfDay(BAKU).toInstant());
        assertThat(instant(event, "end")).as("local midnight after its last day")
                .isEqualTo(LocalDate.of(2204, 3, 4).atStartOfDay(BAKU).toInstant());
        assertThat(event.path("courseType").asText()).isEqualTo("OFFLINE");
        assertThat(event.path("seatLimit").asInt()).isEqualTo(30);

        assertThat(event(data, "course:" + untimedId).path("title").asText()).isEqualTo(untimed.get("title"));
        assertThat(idsOfCourse(data, pendingOnlyId)).containsExactly("course:" + pendingOnlyId);
        assertThat(ids(data)).doesNotContain("course:" + endedBefore, "course:" + startsAfter, "booking:" + pending);
    }

    @Test
    void aRoomBookingOfNoCourseIsTitledByTheRoomAndWhoAskedForIt() {
        LocalDate from = LocalDate.of(2205, 5, 1);
        TestTutor tutor = newApprovedTutor("Huseynova");
        UUID room = room("Room " + word());
        String roomName = roomName(room);
        UUID approved = booking(room, null, tutor, at(from.plusDays(1), 9, 0), at(from.plusDays(1), 11, 0), "APPROVED");
        UUID pending = booking(room, null, tutor, at(from.plusDays(1), 10, 0), at(from.plusDays(1), 12, 0), "PENDING");
        UUID rejected = booking(room, null, tutor, at(from.plusDays(2), 9, 0), at(from.plusDays(2), 11, 0), "REJECTED");
        UUID cancelled = booking(room, null, tutor, at(from.plusDays(3), 9, 0), at(from.plusDays(3), 11, 0), "CANCELLED");
        UUID deleted = booking(room, null, tutor, at(from.plusDays(4), 9, 0), at(from.plusDays(4), 11, 0), "PENDING");
        jdbc.update("update room_bookings set deleted_at = now() where id = ?", deleted);

        JsonNode data = calendar(superToken(), from, from.plusMonths(1)).expectStatus(200).data();

        JsonNode event = event(data, "booking:" + approved);
        assertThat(event.path("kind").asText()).isEqualTo("ROOM_BOOKING");
        assertThat(event.path("title").asText()).isEqualTo(roomName + " · Test H.");
        assertThat(instant(event, "start")).isEqualTo(at(from.plusDays(1), 9, 0));
        assertThat(instant(event, "end")).isEqualTo(at(from.plusDays(1), 11, 0));
        assertThat(event.path("allDay").asBoolean(true)).isFalse();
        assertThat(event.path("roomName").asText()).isEqualTo(roomName);
        assertThat(event.path("bookingStatus").asText()).isEqualTo("APPROVED");
        assertThat(Json.texts(event.path("tutorNames"))).containsExactly("Test Huseynova");
        for (String none : List.of("courseId", "courseSlug", "courseType", "courseStatus", "city", "addressLine",
                "seatLimit", "enrolledCount")) {
            assertThat(event.path(none).isNull()).as(none).isTrue();
        }
        assertThat(event(data, "booking:" + pending).path("bookingStatus").asText()).isEqualTo("PENDING");
        assertThat(ids(data)).doesNotContain("booking:" + rejected, "booking:" + cancelled, "booking:" + deleted);
    }

    @Test
    void onlineArchivedAndDeletedCoursesAreLeftOut() {
        LocalDate day = LocalDate.of(2206, 6, 10);
        LocalDate from = day.withDayOfMonth(1);
        TestTutor tutor = newApprovedTutor("Ismayilov");
        String admin = newAdminToken();
        UUID online = createAsAdmin(tutor, courseRequest("calendar-online", true));
        UUID live = createAsAdmin(tutor, oneTimeRequest(day, "09:00", "10:00", 10));
        UUID archived = createAsAdmin(tutor, oneTimeRequest(day, "11:00", "12:00", 10));
        api.post("/api/admin/courses/" + archived + "/archive").bearer(admin).send().expectStatus(200);
        UUID deleted = createAsAdmin(tutor, oneTimeRequest(day, "13:00", "14:00", 10));
        jdbc.update("update courses set deleted_at = now() where id = ?", deleted);
        // Nothing hanging off an archived OFFLINE course shows either: not its sessions, and not its
        // bookings, which belong to a course and so are not bookings of nobody's.
        UUID archivedOffline = createAsAdmin(tutor, offlineRequest(from, from.plusDays(20), null, null));
        UUID room = room("Room " + word());
        UUID session = session(archivedOffline, at(day, 15, 0), at(day, 16, 0), null, null, "SCHEDULED");
        UUID booking = booking(room, archivedOffline, tutor, at(day, 17, 0), at(day, 18, 0), "APPROVED");
        api.post("/api/admin/courses/" + archivedOffline + "/archive").bearer(admin).send().expectStatus(200);

        JsonNode data = calendar(superToken(), from, from.plusMonths(1)).expectStatus(200).data();

        assertThat(ids(data)).contains("one-time:" + live)
                .doesNotContain("one-time:" + archived, "one-time:" + deleted, "course:" + archivedOffline,
                        "session:" + session, "booking:" + booking);
        assertThat(idsOfCourse(data, online)).isEmpty();
        assertThat(idsOfCourse(data, archived)).isEmpty();
        assertThat(idsOfCourse(data, deleted)).isEmpty();
        assertThat(idsOfCourse(data, archivedOffline)).isEmpty();
    }

    // ── the window's edges, the order and the cap ──────────────────────

    @Test
    void anEventIsShownWhenItOverlapsTheHalfOpenWindowAndTheListIsSortedByStart() {
        LocalDate from = LocalDate.of(2207, 7, 1);
        LocalDate to = LocalDate.of(2207, 7, 8);
        Instant start = from.atStartOfDay(BAKU).toInstant();
        Instant end = to.atStartOfDay(BAKU).toInstant();
        TestTutor tutor = newApprovedTutor("Rzayev");
        UUID room = room("Room " + word());
        Duration hour = Duration.ofHours(1);
        UUID endsAtStart = booking(room, null, tutor, start.minus(hour), start, "APPROVED");
        UUID crossesStart = booking(room, null, tutor, start.minus(hour).plusSeconds(1800), start.plus(hour), "PENDING");
        UUID inside = booking(room, null, tutor, start.plus(Duration.ofDays(3)), start.plus(Duration.ofDays(3)).plus(hour), "APPROVED");
        UUID crossesEnd = booking(room, null, tutor, end.minus(hour), end.plus(hour), "APPROVED");
        UUID afterEnd = booking(room, null, tutor, end.plus(hour), end.plus(hour).plus(hour), "APPROVED");
        UUID startsExactlyAtEnd = booking(room("Room " + word()), null, tutor, end, end.plus(hour), "APPROVED");
        UUID lastDay = createAsAdmin(tutor, oneTimeRequest(to.minusDays(1), "08:00", "09:00", 5));
        UUID dayAfter = createAsAdmin(tutor, oneTimeRequest(to, "08:00", "09:00", 5));

        JsonNode data = calendar(superToken(), from, to).expectStatus(200).data();

        List<String> mine = new ArrayList<>(ids(data));
        mine.retainAll(List.of("booking:" + endsAtStart, "booking:" + crossesStart, "booking:" + inside,
                "booking:" + crossesEnd, "booking:" + afterEnd, "booking:" + startsExactlyAtEnd,
                "one-time:" + lastDay, "one-time:" + dayAfter));
        assertThat(mine).containsExactly("booking:" + crossesStart, "booking:" + inside,
                "one-time:" + lastDay, "booking:" + crossesEnd);

        List<Instant> starts = new ArrayList<>();
        data.path("events").forEach(e -> starts.add(Instant.parse(e.path("start").asText())));
        assertThat(starts).isSorted();
    }

    /**
     * 2001 approved slots back to back in one room, cleaned up afterwards so that no other class's
     * booking list or count meets them.
     */
    @Test
    void aWindowWithMoreThan2000EventsListsTheFirst2000AndSaysSo() {
        LocalDate from = LocalDate.of(2208, 1, 1);
        TestTutor tutor = newApprovedTutor("Crowded");
        UUID room = room("Room " + word());
        Instant first = at(from, 0, 0);
        List<Object[]> rows = new ArrayList<>();
        for (int i = 0; i < 2001; i++) {
            Instant slot = first.plus(Duration.ofMinutes(30L * i));
            rows.add(new Object[]{UUID.randomUUID(), room, tutor.profileId(), utc(slot), utc(slot.plusSeconds(1800))});
        }
        try {
            jdbc.batchUpdate("""
                    insert into room_bookings (id, room_id, tutor_id, starts_at, ends_at, status)
                    values (?, ?, ?, ?, ?, 'APPROVED')
                    """, rows);

            JsonNode data = calendar(superToken(), from, from.plusDays(62)).expectStatus(200).data();

            assertThat(data.path("truncated").asBoolean(false)).isTrue();
            assertThat(data.path("events").size()).isEqualTo(2000);
            assertThat(data.path("events").get(0).path("id").asText()).isEqualTo("booking:" + rows.get(0)[0]);
            assertThat(ids(data)).contains("booking:" + rows.get(1999)[0]).doesNotContain("booking:" + rows.get(2000)[0]);
        } finally {
            jdbc.update("delete from room_bookings where room_id = ?", room);
        }
    }

    // ── helpers ─────────────────────────────────────────────────────────

    private ApiClient.Response calendar(String token, LocalDate from, LocalDate to) {
        return api.get("/api/admin/calendar").bearer(token).query("from", from).query("to", to).send();
    }

    private String superToken() {
        return login(newUser("calendar-super", "SUPER_ADMIN").email());
    }

    private UUID createAsAdmin(TestTutor tutor, Map<String, Object> course) {
        return UUID.fromString(api.post("/api/admin/courses").bearer(newAdminToken())
                .json(Json.object("course", course,
                        "tutorIds", List.of(tutor.profileId()),
                        "authorizedTutorId", tutor.profileId()))
                .send().expectStatus(201).data().path("id").asText());
    }

    private static Map<String, Object> oneTimeRequest(LocalDate day, String startTime, String endTime, int seats) {
        Map<String, Object> request = courseRequest("calendar-one-time", true);
        request.put("courseType", "ONE_TIME");
        request.remove("onlineDetails");
        request.put("offlineDetails", Json.object("startDate", day.toString(), "startTime", startTime,
                "endTime", endTime, "studentLimit", seats, "city", "Baku", "addressLine", "AzTU, 20 H. Javid Ave"));
        return request;
    }

    private static Map<String, Object> offlineRequest(LocalDate start, LocalDate end, String startTime, String endTime) {
        Map<String, Object> request = courseRequest("calendar-offline", true);
        request.put("courseType", "OFFLINE");
        request.remove("onlineDetails");
        request.put("offlineDetails", Json.object("startDate", start.toString(), "endDate", end.toString(),
                "startTime", startTime, "endTime", endTime, "studentLimit", 30));
        return request;
    }

    private UUID room(String name) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                insert into rooms (id, name, room_number, building, capacity, status)
                values (?, ?, ?, 'IT-calendar', 20, 'AVAILABLE')
                """, id, name, word());
        return id;
    }

    private String roomName(UUID room) {
        return jdbc.queryForObject("select name from rooms where id = ?", String.class, room);
    }

    private UUID booking(UUID room, UUID course, TestTutor tutor, Instant start, Instant end, String status) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                insert into room_bookings (id, room_id, offline_course_id, tutor_id, starts_at, ends_at, status)
                values (?, ?, ?, ?, ?, ?, ?)
                """, id, room, course, tutor.profileId(), utc(start), utc(end), status);
        return id;
    }

    private UUID session(UUID course, Instant start, Instant end, String topic, UUID booking, String status) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                insert into offline_sessions (id, offline_course_id, booking_id, session_date, starts_at, ends_at, topic, status)
                values (?, ?, ?, ?, ?, ?, ?, ?)
                """, id, course, booking, LocalDate.ofInstant(start, BAKU), utc(start), utc(end), topic, status);
        return id;
    }

    private static Instant at(LocalDate day, int hour, int minute) {
        return day.atTime(LocalTime.of(hour, minute)).atZone(BAKU).toInstant();
    }

    /** As an offset: the driver has no mapping for Instant. */
    private static OffsetDateTime utc(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    private static Instant instant(JsonNode event, String field) {
        return Instant.parse(event.path(field).asText());
    }

    private static List<String> ids(JsonNode data) {
        List<String> out = new ArrayList<>();
        data.path("events").forEach(e -> out.add(e.path("id").asText()));
        return out;
    }

    private static List<String> idsOfCourse(JsonNode data, UUID courseId) {
        List<String> out = new ArrayList<>();
        data.path("events").forEach(e -> {
            if (courseId.toString().equals(e.path("courseId").asText())) out.add(e.path("id").asText());
        });
        return out;
    }

    private static JsonNode event(JsonNode data, String id) {
        for (JsonNode e : data.path("events")) {
            if (id.equals(e.path("id").asText())) return e;
        }
        throw new AssertionError("no event " + id + " in " + data);
    }
}
