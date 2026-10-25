package com.eduplatform.eduplatform_backend.room;

import com.eduplatform.eduplatform_backend.support.AbstractIntegrationTest;
import com.eduplatform.eduplatform_backend.support.ApiClient;
import com.eduplatform.eduplatform_backend.support.Json;
import com.eduplatform.eduplatform_backend.support.TestFiles;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Long-term room bookings: one request for a room on chosen weekdays over a period, stored as one
 * ordinary booking per occurrence and decided once, as a whole.
 */
class RoomBookingSeriesTest extends AbstractIntegrationTest {

    private static final ZoneId BAKU = ZoneId.of("Asia/Baku");
    private static final String SERIES = "/api/portal/room-bookings/series";

    /** A Monday at least a week ahead, so every occurrence of a short series lies in the future. */
    private static LocalDate monday() {
        return LocalDate.now(BAKU).plusDays(7).with(TemporalAdjusters.nextOrSame(DayOfWeek.MONDAY));
    }

    @Test
    void aSeriesIsOnePendingBookingPerOccurrencePricedLikeASingleBooking() {
        String adminToken = newAdminToken();
        UUID room = newRoom(adminToken, "AVAILABLE", "10.00");
        // Monday mornings cost more, exactly as they would for a single booking.
        api.post("/api/admin/rooms/" + room + "/pricing-rules").bearer(adminToken)
                .json(Json.object("name", "Monday mornings", "hourlyRate", 33, "currency", "AZN",
                        "dayOfWeek", 1, "startTime", "09:00", "endTime", "12:00", "priority", 5))
                .send().expectStatus(201);
        TestTutor tutor = newApprovedTutor("series");
        LocalDate monday = monday();

        Map<String, Object> request = request(room, monday, monday.plusDays(13), List.of("MONDAY", "WEDNESDAY"),
                "10:00", "12:00:00");
        request.put("note", "  Python course, group B  ");
        JsonNode created = api.post(SERIES).bearer(login(tutor.email())).json(request)
                .send().expectStatus(201).data();

        assertThat(created.path("status").asText()).isEqualTo("PENDING");
        assertThat(created.path("roomId").asText()).isEqualTo(room.toString());
        assertThat(created.path("roomName").asText()).isNotBlank();
        assertThat(created.path("tutorId").asText()).isEqualTo(tutor.profileId().toString());
        assertThat(created.path("tutorEmail").asText()).isEqualTo(tutor.email());
        assertThat(created.path("startDate").asText()).isEqualTo(monday.toString());
        assertThat(created.path("endDate").asText()).isEqualTo(monday.plusDays(13).toString());
        assertThat(Json.texts(created.path("daysOfWeek"))).containsExactly("MONDAY", "WEDNESDAY");
        assertThat(created.path("startTime").asText()).isEqualTo("10:00");
        assertThat(created.path("endTime").asText()).isEqualTo("12:00");
        assertThat(created.path("timezone").asText()).isEqualTo("Asia/Baku");
        assertThat(created.path("note").asText()).isEqualTo("Python course, group B");
        assertThat(created.path("occurrenceCount").asInt()).isEqualTo(4);
        assertThat(created.path("upcomingCount").asInt()).isEqualTo(4);
        // Two Mondays at 33/h and two Wednesdays at the flat 10/h, two hours each.
        assertThat(created.path("totalFee").decimalValue()).isEqualByComparingTo("172.00");
        assertThat(created.path("currency").asText()).isEqualTo("AZN");
        assertThat(created.path("decidedAt").isNull()).isTrue();

        List<LocalDate> dates = List.of(monday, monday.plusDays(2), monday.plusDays(7), monday.plusDays(9));
        JsonNode occurrences = created.path("occurrences");
        assertThat(occurrences).hasSize(4);
        for (int i = 0; i < dates.size(); i++) {
            JsonNode occurrence = occurrences.get(i);
            assertThat(Instant.parse(occurrence.path("startsAt").asText()))
                    .isEqualTo(dates.get(i).atTime(10, 0).atZone(BAKU).toInstant());
            assertThat(Instant.parse(occurrence.path("endsAt").asText()))
                    .isEqualTo(dates.get(i).atTime(12, 0).atZone(BAKU).toInstant());
            assertThat(occurrence.path("status").asText()).isEqualTo("PENDING");
        }

        String seriesId = created.path("id").asText();
        assertThat(jdbc.queryForList("select total_fee::text from room_bookings where series_id = ?::uuid "
                + "and status = 'PENDING' order by starts_at", String.class, seriesId))
                .containsExactly("66.00", "20.00", "66.00", "20.00");
    }

    /** The period may cover 366 days, startDate and endDate included, and not one more. */
    @Test
    void aSeriesCoversAYearAtMost() {
        UUID room = newRoom(newAdminToken(), "AVAILABLE", "10.00");
        String tutorToken = login(newApprovedTutor("year").email());
        LocalDate monday = monday();

        api.post(SERIES).bearer(tutorToken)
                .json(request(room, monday, monday.plusDays(366), List.of("MONDAY"), "10:00", "11:00"))
                .send().expectError(400, "INVALID_DATE_RANGE");
        JsonNode year = api.post(SERIES).bearer(tutorToken)
                .json(request(room, monday, monday.plusDays(365), List.of("MONDAY"), "10:00", "11:00"))
                .send().expectStatus(201).data();
        assertThat(year.path("occurrenceCount").asInt()).isEqualTo(53);
    }

    @Test
    void eachBrokenRuleHasItsOwnCode() {
        UUID room = newRoom(newAdminToken(), "AVAILABLE", "10.00");
        String tutorToken = login(newApprovedTutor("rules").email());
        LocalDate monday = monday();

        post(tutorToken, request(room, monday, monday.minusDays(1), List.of("MONDAY"), "10:00", "12:00"))
                .expectError(400, "INVALID_DATE_RANGE");
        post(tutorToken, request(room, monday, monday.plusDays(7), List.of("MONDAY"), "12:00", "12:00"))
                .expectError(400, "INVALID_TIME_RANGE");
        post(tutorToken, request(room, monday, monday.plusDays(7), List.of("MONDAY"), "12:00", "10:00"))
                .expectError(400, "INVALID_TIME_RANGE");
        post(tutorToken, request(room, monday, monday.plusDays(7), List.of(), "10:00", "12:00"))
                .expectError(400, "DAYS_REQUIRED");
        post(tutorToken, request(room, monday, monday.plusDays(7), null, "10:00", "12:00"))
                .expectError(400, "DAYS_REQUIRED");
        // Tuesday to Saturday holds no Monday.
        post(tutorToken, request(room, monday.plusDays(1), monday.plusDays(5), List.of("MONDAY"), "10:00", "12:00"))
                .expectError(400, "NO_OCCURRENCES");
        post(tutorToken, request(room, monday, monday.plusDays(299), allDays(), "10:00", "12:00"))
                .expectError(400, "TOO_MANY_OCCURRENCES");
        LocalDate yesterday = LocalDate.now(BAKU).minusDays(1);
        post(tutorToken, request(room, yesterday, yesterday.plusDays(14), allDays(), "10:00", "12:00"))
                .expectError(400, "PAST_START");
        Map<String, Object> longNote = request(room, monday, monday.plusDays(7), List.of("MONDAY"), "10:00", "12:00");
        longNote.put("note", "x".repeat(501));
        post(tutorToken, longNote).expectError(400, "VALIDATION_FAILED");
        Map<String, Object> badDay = request(room, monday, monday.plusDays(7), List.of("MONDAYS"), "10:00", "12:00");
        post(tutorToken, badDay).expectStatus(400);

        assertThat(jdbc.queryForObject("select count(*) from room_booking_series where room_id = ?",
                Integer.class, room)).isZero();
    }

    /** The room and the training are checked exactly as for a single booking. */
    @Test
    void theRoomAndTheTrainingMustBeBookableAsForASingleBooking() {
        String adminToken = newAdminToken();
        UUID maintenance = newRoom(adminToken, "MAINTENANCE", "10.00");
        UUID room = newRoom(adminToken, "AVAILABLE", "10.00");
        String tutorToken = login(newApprovedTutor("bookable").email());
        LocalDate monday = monday();

        post(tutorToken, request(maintenance, monday, monday.plusDays(7), List.of("MONDAY"), "10:00", "12:00"))
                .expectError(409, "ROOM_NOT_AVAILABLE");
        post(tutorToken, request(UUID.randomUUID(), monday, monday.plusDays(7), List.of("MONDAY"), "10:00", "12:00"))
                .expectError(404, "ROOM_NOT_FOUND");

        Map<String, Object> course = courseRequest("in-person", true);
        course.put("courseType", "OFFLINE");
        course.remove("onlineDetails");
        course.put("offlineDetails", Json.object("startDate", monday.toString(),
                "endDate", monday.plusDays(30).toString(), "studentLimit", 10));
        CourseRef taught = createCourse(tutorToken, course);
        Map<String, Object> forCourse = request(room, monday, monday.plusDays(7), List.of("MONDAY"), "10:00", "12:00");
        forCourse.put("offlineCourseId", taught.id());
        JsonNode created = post(tutorToken, forCourse).expectStatus(201).data();
        assertThat(created.path("offlineCourseId").asText()).isEqualTo(taught.id().toString());
        assertThat(created.path("offlineCourseTitle").asText()).isEqualTo(taught.title());

        Map<String, Object> notTaught = request(room, monday.plusDays(14), monday.plusDays(21), List.of("MONDAY"),
                "10:00", "12:00");
        notTaught.put("offlineCourseId", taught.id());
        post(login(newApprovedTutor("other").email()), notTaught).expectError(403, "NOT_COURSE_TUTOR");
        // Someone without room:book cannot ask at all.
        post(login(newUser("learner", "USER").email()), notTaught).expectError(403, "FORBIDDEN");
    }

    @Test
    void anOccurrenceOnAnApprovedBookingIsRefusedNamingTheDates() {
        String adminToken = newAdminToken();
        UUID room = newRoom(adminToken, "AVAILABLE", "10.00");
        String tutorToken = login(newApprovedTutor("conflict").email());
        LocalDate monday = monday();
        LocalDate secondMonday = monday.plusDays(7);
        String single = book(tutorToken, room, secondMonday.atTime(11, 0), secondMonday.atTime(13, 0))
                .expectStatus(201).data().path("id").asText();
        decideSingle(adminToken, single, "APPROVED").expectStatus(200);

        String message = post(tutorToken, request(room, monday, monday.plusDays(13), List.of("MONDAY", "WEDNESDAY"),
                "10:00", "12:00")).expectError(409, "ROOM_BOOKING_CONFLICT").json().path("message").asText();
        assertThat(message).contains(secondMonday.toString()).doesNotContain(monday.toString());
        assertThat(jdbc.queryForObject("select count(*) from room_booking_series where room_id = ?",
                Integer.class, room)).isZero();

        // Many dates: ten are named, the rest counted.
        String first = post(tutorToken, request(room, monday.plusDays(21), monday.plusDays(32), allDays(),
                "15:00", "16:00")).expectStatus(201).data().path("id").asText();
        decideSeries(adminToken, first, "APPROVED", null).expectStatus(200);
        String many = post(tutorToken, request(room, monday.plusDays(21), monday.plusDays(32), allDays(),
                "15:30", "17:00")).expectError(409, "ROOM_BOOKING_CONFLICT").json().path("message").asText();
        assertThat(many).contains(monday.plusDays(21).toString()).endsWith(" and 2 more");
    }

    @Test
    void onlyTheRequesterOrAModeratorCanSeeASeries() {
        String adminToken = newAdminToken();
        UUID room = newRoom(adminToken, "AVAILABLE", "10.00");
        String ownerToken = login(newApprovedTutor("owner").email());
        String otherToken = login(newApprovedTutor("other").email());
        LocalDate monday = monday();
        String id = post(ownerToken, request(room, monday, monday.plusDays(13), List.of("MONDAY"), "10:00", "12:00"))
                .expectStatus(201).data().path("id").asText();

        JsonNode own = api.get(SERIES + "/" + id).bearer(ownerToken).send().expectStatus(200).data();
        assertThat(own.path("occurrences")).hasSize(2);
        JsonNode moderated = api.get(SERIES + "/" + id).bearer(adminToken).send().expectStatus(200).data();
        assertThat(moderated.path("occurrences")).hasSize(2);
        api.get(SERIES + "/" + id).bearer(otherToken).send().expectError(403, "NOT_BOOKING_OWNER");
        api.delete(SERIES + "/" + id).bearer(otherToken).send().expectError(403, "NOT_BOOKING_OWNER");
        api.get(SERIES + "/" + id).bearer(login(newUser("learner", "USER").email())).send()
                .expectError(403, "FORBIDDEN");
        api.get(SERIES + "/" + UUID.randomUUID()).bearer(ownerToken).send()
                .expectError(404, "BOOKING_SERIES_NOT_FOUND");

        JsonNode mine = api.get(SERIES + "/mine").bearer(ownerToken).send().expectStatus(200).data();
        assertThat(Json.field(mine, "id")).containsExactly(id);
        assertThat(Json.find(mine, "id", id).path("occurrences")).isEmpty();
        assertThat(Json.find(mine, "id", id).path("occurrenceCount").asInt()).isEqualTo(2);
        assertThat(Json.field(api.get(SERIES + "/mine").bearer(otherToken).send().expectStatus(200).data(), "id"))
                .isEmpty();
        // Deciding is for moderators only.
        api.get(SERIES + "/admin").bearer(ownerToken).send().expectError(403, "FORBIDDEN");
        decideSeries(ownerToken, id, "APPROVED", null).expectError(403, "FORBIDDEN");
    }

    @Test
    void theAdminQueueListsPendingSeriesOldestFirstAndCapsThePage() {
        String adminToken = newAdminToken();
        UUID room = newRoom(adminToken, "AVAILABLE", "10.00");
        String tutorToken = login(newApprovedTutor("queue").email());
        LocalDate monday = monday();
        String older = post(tutorToken, request(room, monday, monday.plusDays(6), List.of("MONDAY"), "10:00", "11:00"))
                .expectStatus(201).data().path("id").asText();
        String newer = post(tutorToken, request(room, monday, monday.plusDays(6), List.of("MONDAY"), "11:00", "12:00"))
                .expectStatus(201).data().path("id").asText();

        JsonNode queue = api.get(SERIES + "/admin").bearer(adminToken).query("status", "PENDING")
                .query("size", 1000).query("sort", "createdAt,desc").send().expectStatus(200).data();
        assertThat(queue.path("size").asInt()).isEqualTo(100);
        List<String> ids = Json.field(queue, "id");
        assertThat(ids).contains(older, newer);
        assertThat(ids.indexOf(older)).isLessThan(ids.indexOf(newer));
        assertThat(Json.find(queue, "id", older).path("occurrences")).isEmpty();

        JsonNode mine = api.get(SERIES + "/mine").bearer(tutorToken).send().expectStatus(200).data();
        assertThat(Json.field(mine, "id")).containsExactly(newer, older);
    }

    @Test
    void approvingApprovesEveryOccurrenceAndTellsTheExpert() {
        String adminToken = newAdminToken();
        UUID room = newRoom(adminToken, "AVAILABLE", "10.00");
        TestTutor tutor = newApprovedTutor("approve");
        LocalDate monday = monday();
        String id = post(login(tutor.email()), request(room, monday, monday.plusDays(13),
                List.of("MONDAY", "WEDNESDAY"), "10:00", "12:00")).expectStatus(201).data().path("id").asText();

        JsonNode decided = decideSeries(adminToken, id, "APPROVED", null).expectStatus(200).data();
        assertThat(decided.path("status").asText()).isEqualTo("APPROVED");
        assertThat(decided.path("occurrences")).hasSize(4);
        decided.path("occurrences").forEach(o -> assertThat(o.path("status").asText()).isEqualTo("APPROVED"));
        assertThat(decided.path("upcomingCount").asInt()).isEqualTo(4);
        Instant decidedAt = Instant.parse(decided.path("decidedAt").asText());

        JsonNode reread = api.get(SERIES + "/" + id).bearer(adminToken).send().expectStatus(200).data();
        assertThat(Instant.parse(reread.path("decidedAt").asText()).truncatedTo(ChronoUnit.MICROS))
                .isEqualTo(decidedAt.truncatedTo(ChronoUnit.MICROS));
        assertThat(jdbc.queryForList("select status from room_bookings where series_id = ?::uuid", String.class, id))
                .hasSize(4).containsOnly("APPROVED");

        Map<String, Object> notification = latestNotification(tutor.userId());
        assertThat(notification.get("template_code")).isEqualTo("room_booking.approved");
        assertThat((String) notification.get("payload")).contains(id);

        decideSeries(adminToken, id, "REJECTED", "too late").expectError(409, "INVALID_BOOKING_TRANSITION");
    }

    @Test
    void anApprovalThatWouldDoubleBookChangesNothing() {
        String adminToken = newAdminToken();
        UUID room = newRoom(adminToken, "AVAILABLE", "10.00");
        String tutorToken = login(newApprovedTutor("double").email());
        LocalDate monday = monday();
        String id = post(tutorToken, request(room, monday, monday.plusDays(13), List.of("MONDAY", "WEDNESDAY"),
                "10:00", "12:00")).expectStatus(201).data().path("id").asText();
        // Approved after the series was requested, for the second Wednesday.
        LocalDate wednesday = monday.plusDays(9);
        String single = book(login(newApprovedTutor("rival").email()), room, wednesday.atTime(9, 0),
                wednesday.atTime(10, 30)).expectStatus(201).data().path("id").asText();
        decideSingle(adminToken, single, "APPROVED").expectStatus(200);

        String message = decideSeries(adminToken, id, "APPROVED", null)
                .expectError(409, "ROOM_BOOKING_CONFLICT").json().path("message").asText();
        assertThat(message).contains(wednesday.toString());
        assertThat(jdbc.queryForObject("select status from room_booking_series where id = ?::uuid", String.class, id))
                .isEqualTo("PENDING");
        assertThat(jdbc.queryForList("select status from room_bookings where series_id = ?::uuid", String.class, id))
                .hasSize(4).containsOnly("PENDING");
    }

    @Test
    void rejectingNeedsANoteAndRejectsEveryOccurrence() {
        String adminToken = newAdminToken();
        UUID room = newRoom(adminToken, "AVAILABLE", "10.00");
        TestTutor tutor = newApprovedTutor("reject");
        LocalDate monday = monday();
        String id = post(login(tutor.email()), request(room, monday, monday.plusDays(13), List.of("MONDAY"),
                "10:00", "12:00")).expectStatus(201).data().path("id").asText();

        decideSeries(adminToken, id, "REJECTED", null).expectError(400, "NOTE_REQUIRED");
        decideSeries(adminToken, id, "REJECTED", "   ").expectError(400, "NOTE_REQUIRED");
        JsonNode rejected = decideSeries(adminToken, id, "REJECTED", "The room is reserved for exams")
                .expectStatus(200).data();
        assertThat(rejected.path("status").asText()).isEqualTo("REJECTED");
        assertThat(rejected.path("decisionNote").asText()).isEqualTo("The room is reserved for exams");
        assertThat(rejected.path("upcomingCount").asInt()).isZero();
        rejected.path("occurrences").forEach(o -> assertThat(o.path("status").asText()).isEqualTo("REJECTED"));

        Map<String, Object> notification = latestNotification(tutor.userId());
        assertThat(notification.get("template_code")).isEqualTo("room_booking.rejected");
        assertThat((String) notification.get("body")).contains("The room is reserved for exams");

        api.delete(SERIES + "/" + id).bearer(login(tutor.email())).send()
                .expectError(409, "INVALID_BOOKING_TRANSITION");
    }

    @Test
    void cancellingWithdrawsOnlyTheOccurrencesStillToCome() {
        String adminToken = newAdminToken();
        UUID room = newRoom(adminToken, "AVAILABLE", "10.00");
        String tutorToken = login(newApprovedTutor("cancel").email());
        LocalDate monday = monday();
        String id = post(tutorToken, request(room, monday, monday.plusDays(13), List.of("MONDAY", "WEDNESDAY"),
                "10:00", "12:00")).expectStatus(201).data().path("id").asText();
        decideSeries(adminToken, id, "APPROVED", null).expectStatus(200);
        // The first occurrence has taken place by the time the expert cancels.
        UUID past = jdbc.queryForObject("select id from room_bookings where series_id = ?::uuid "
                + "order by starts_at limit 1", UUID.class, id);
        jdbc.update("update room_bookings set starts_at = now() - interval '3 hours', "
                + "ends_at = now() - interval '1 hour' where id = ?", past);

        api.delete(SERIES + "/" + id).bearer(tutorToken).send().expectStatus(204);

        JsonNode cancelled = api.get(SERIES + "/" + id).bearer(tutorToken).send().expectStatus(200).data();
        assertThat(cancelled.path("status").asText()).isEqualTo("CANCELLED");
        assertThat(cancelled.path("upcomingCount").asInt()).isZero();
        List<String> statuses = new ArrayList<>();
        cancelled.path("occurrences").forEach(o -> statuses.add(o.path("status").asText()));
        assertThat(statuses).containsExactly("APPROVED", "CANCELLED", "CANCELLED", "CANCELLED");
        assertThat(cancelled.path("occurrences").get(0).path("bookingId").asText()).isEqualTo(past.toString());

        api.delete(SERIES + "/" + id).bearer(tutorToken).send().expectStatus(204);
        decideSeries(adminToken, id, "APPROVED", null).expectError(409, "INVALID_BOOKING_TRANSITION");
    }

    /** Occurrences are managed through their series; the single-booking endpoints leave them be. */
    @Test
    void theSingleBookingEndpointsLeaveOccurrencesToTheirSeries() {
        String adminToken = newAdminToken();
        UUID room = newRoom(adminToken, "AVAILABLE", "10.00");
        TestTutor tutor = newApprovedTutor("singles");
        String tutorToken = login(tutor.email());
        LocalDate monday = monday();
        String seriesId = post(tutorToken, request(room, monday, monday.plusDays(6), List.of("MONDAY"),
                "10:00", "12:00")).expectStatus(201).data().path("id").asText();
        String occurrence = jdbc.queryForObject("select id::text from room_bookings where series_id = ?::uuid",
                String.class, seriesId);
        LocalDate tuesday = monday.plusDays(1);
        String single = book(tutorToken, room, tuesday.atTime(10, 0), tuesday.atTime(12, 0))
                .expectStatus(201).data().path("id").asText();

        JsonNode mine = api.get("/api/portal/room-bookings/mine").bearer(tutorToken).send().expectStatus(200).data();
        assertThat(Json.field(mine, "id")).containsExactly(single);
        assertThat(Json.find(mine, "id", single).has("seriesId")).isTrue();
        assertThat(Json.find(mine, "id", single).path("seriesId").isNull()).isTrue();
        JsonNode queue = api.get("/api/portal/room-bookings/admin").bearer(adminToken).query("status", "PENDING")
                .query("size", 100).send().expectStatus(200).data();
        assertThat(Json.field(queue, "id")).doesNotContain(occurrence);
        queue.path("content").forEach(b -> assertThat(b.path("seriesId").isNull()).isTrue());

        api.delete("/api/portal/room-bookings/" + occurrence).bearer(tutorToken).send()
                .expectError(409, "PART_OF_SERIES");
        decideSingle(adminToken, occurrence, "APPROVED").expectError(409, "PART_OF_SERIES");
        assertThat(jdbc.queryForObject("select status from room_bookings where id = ?::uuid", String.class, occurrence))
                .isEqualTo("PENDING");

        // A single booking's decision reaches the tutor through the same path as a series'.
        decideSingle(adminToken, single, "APPROVED").expectStatus(200);
        Map<String, Object> notification = latestNotification(tutor.userId());
        assertThat(notification.get("template_code")).isEqualTo("room_booking.approved");
        assertThat((String) notification.get("payload")).contains(single);
    }

    // ── helpers ─────────────────────────────────────────────────────────

    private static Map<String, Object> request(UUID room, LocalDate from, LocalDate to, List<String> days,
                                               String startTime, String endTime) {
        return Json.object("roomId", room, "startDate", from.toString(), "endDate", to.toString(),
                "daysOfWeek", days, "startTime", startTime, "endTime", endTime);
    }

    private static List<String> allDays() {
        return List.of("MONDAY", "TUESDAY", "WEDNESDAY", "THURSDAY", "FRIDAY", "SATURDAY", "SUNDAY");
    }

    private ApiClient.Response post(String tutorToken, Map<String, Object> request) {
        return api.post(SERIES).bearer(tutorToken).json(request).send();
    }

    private ApiClient.Response decideSeries(String token, String seriesId, String decision, String note) {
        return api.post(SERIES + "/admin/" + seriesId + "/decision").bearer(token)
                .json(Json.object("decision", decision, "note", note)).send();
    }

    private ApiClient.Response decideSingle(String adminToken, String bookingId, String decision) {
        return api.post("/api/portal/room-bookings/admin/" + bookingId + "/decision").bearer(adminToken)
                .json(Json.object("decision", decision, "note", "integration test")).send();
    }

    private ApiClient.Response book(String tutorToken, UUID room, LocalDateTime from, LocalDateTime to) {
        return api.post("/api/portal/room-bookings").bearer(tutorToken)
                .json(Json.object("roomId", room, "startsAt", from.atZone(BAKU).toInstant().toString(),
                        "endsAt", to.atZone(BAKU).toInstant().toString()))
                .send();
    }

    /** Written once the decision has committed, before the response is sent. */
    private Map<String, Object> latestNotification(UUID userId) {
        return jdbc.queryForMap("select template_code, body, payload::text as payload from notifications "
                + "where user_id = ? order by created_at desc limit 1", userId);
    }

    private UUID newRoom(String adminToken, String status, String hourlyRate) {
        UUID a = uploadMedia(adminToken, "room-a.png", "image/png", TestFiles.png());
        UUID b = uploadMedia(adminToken, "room-b.png", "image/png", TestFiles.png());
        return UUID.fromString(api.post("/api/admin/rooms").bearer(adminToken)
                .json(Json.object("name", "Room " + word(), "roomNumber", word(), "building", "IT-test",
                        "capacity", 20, "status", status, "hourlyRate", hourlyRate, "currency", "AZN",
                        "imageMediaIds", List.of(a, b)))
                .send().expectStatus(201).data().path("id").asText());
    }
}
