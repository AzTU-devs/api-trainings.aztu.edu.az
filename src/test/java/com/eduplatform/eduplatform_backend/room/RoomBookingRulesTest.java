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
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.temporal.TemporalAdjusters;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Rooms and their bookings: what a room's status, its pricing rules and its deletion mean. */
class RoomBookingRulesTest extends AbstractIntegrationTest {

    private static final ZoneId BAKU = ZoneId.of("Asia/Baku");

    /** A soft delete never fired the foreign key's RESTRICT, and every list holding the booking then failed. */
    @Test
    void aRoomWithBookingsCannotBeDeletedAndListsSurviveOneThatWas() {
        String adminToken = newAdminToken();
        UUID room = newRoom(adminToken, "AVAILABLE", "10.00");
        TestTutor tutor = newApprovedTutor("tutor");
        String tutorToken = login(tutor.email());
        JsonNode booking = book(tutorToken, room, slot(10, 9, 11)).expectStatus(201).data();
        assertThat(booking.path("tutorEmail").asText()).isEqualTo(tutor.email());
        assertThat(booking.path("roomName").asText()).isNotBlank();

        api.delete("/api/admin/rooms/" + room).bearer(adminToken).send().expectError(409, "ROOM_HAS_BOOKINGS");

        // A room deleted before this rule existed: the lists read the name from the row itself.
        jdbc.update("update rooms set deleted_at = now() where id = ?", room);
        JsonNode pending = api.get("/api/portal/room-bookings/admin").bearer(adminToken).query("status", "PENDING")
                .query("size", 100).send().expectStatus(200).data();
        assertThat(Json.find(pending, "id", booking.path("id").asText()).path("roomName").asText()).isNotBlank();
        api.get("/api/portal/room-bookings/mine").bearer(tutorToken).send().expectStatus(200);
        // Approving a booking for a room that is gone is refused; rejecting it still works.
        decide(adminToken, booking.path("id").asText(), "APPROVED").expectError(409, "ROOM_NOT_AVAILABLE");
        decide(adminToken, booking.path("id").asText(), "REJECTED").expectStatus(200);
    }

    @Test
    void aRoomUnderMaintenanceCannotBeBooked() {
        String adminToken = newAdminToken();
        UUID room = newRoom(adminToken, "MAINTENANCE", "10.00");

        book(login(newApprovedTutor("tutor").email()), room, slot(11, 9, 11)).expectError(409, "ROOM_NOT_AVAILABLE");
    }

    /** The second of two overlapping approvals used to fail on the exclusion constraint as a bare 409. */
    @Test
    void approvingAnOverlappingBookingSaysTheTimeIsTaken() {
        String adminToken = newAdminToken();
        UUID room = newRoom(adminToken, "AVAILABLE", "10.00");
        Instant[] when = slot(12, 9, 11);
        String first = book(login(newApprovedTutor("a").email()), room, when).expectStatus(201).data()
                .path("id").asText();
        String second = book(login(newApprovedTutor("b").email()), room, when).expectStatus(201).data()
                .path("id").asText();

        decide(adminToken, first, "APPROVED").expectStatus(200);
        decide(adminToken, second, "APPROVED").expectError(409, "ROOM_TIME_TAKEN");
    }

    /** The pricing page promised that the highest-priority matching rule applies; none ever did. */
    @Test
    void theMatchingPricingRuleSetsTheFee() {
        String adminToken = newAdminToken();
        UUID room = newRoom(adminToken, "AVAILABLE", "22.50");
        api.post("/api/admin/rooms/" + room + "/pricing-rules").bearer(adminToken)
                .json(Json.object("name", "Monday mornings", "hourlyRate", 33, "currency", "AZN",
                        "dayOfWeek", 1, "startTime", "09:00", "endTime", "12:00", "priority", 5))
                .send().expectStatus(201);

        LocalDate monday = LocalDate.now(BAKU).plusDays(1).with(TemporalAdjusters.nextOrSame(DayOfWeek.MONDAY));
        JsonNode mondayMorning = book(login(newApprovedTutor("tutor").email()), room, at(monday, 9, 11))
                .expectStatus(201).data();
        assertThat(mondayMorning.path("totalFee").decimalValue()).isEqualByComparingTo("66.00");
        assertThat(mondayMorning.path("currency").asText()).isEqualTo("AZN");

        JsonNode tuesday = book(login(newApprovedTutor("tutor").email()), room, at(monday.plusDays(1), 9, 11))
                .expectStatus(201).data();
        assertThat(tuesday.path("totalFee").decimalValue()).isEqualByComparingTo("45.00");
    }

    /** Sunday is day 0, as the dashboard numbers it; the database used to refuse it. */
    @Test
    void aSundayPricingRuleCanBeSaved() {
        String adminToken = newAdminToken();
        UUID room = newRoom(adminToken, "AVAILABLE", "10.00");

        api.post("/api/admin/rooms/" + room + "/pricing-rules").bearer(adminToken)
                .json(Json.object("name", "Sundays", "hourlyRate", 5, "currency", "AZN", "dayOfWeek", 0))
                .send().expectStatus(201);
    }

    @Test
    void aRecurringRequestIsRefusedRatherThanPricedAsOneSession() {
        UUID room = newRoom(newAdminToken(), "AVAILABLE", "10.00");
        Instant[] when = slot(13, 9, 11);

        api.post("/api/portal/room-bookings").bearer(login(newApprovedTutor("tutor").email()))
                .json(Json.object("roomId", room, "startsAt", when[0].toString(), "endsAt", when[1].toString(),
                        "recurrenceRule", "FREQ=WEEKLY;COUNT=10"))
                .send().expectError(400, "RECURRENCE_NOT_SUPPORTED");
    }

    /** The training a room is booked for used to be accepted and silently dropped. */
    @Test
    void aBookingKeepsTheTrainingItIsForWhenTheTutorTeachesIt() {
        UUID room = newRoom(newAdminToken(), "AVAILABLE", "10.00");
        String tutorToken = login(newApprovedTutor("tutor").email());
        java.util.Map<String, Object> request = courseRequest("in-person", true);
        request.put("courseType", "OFFLINE");
        request.remove("onlineDetails");
        LocalDate start = LocalDate.now(BAKU).plusDays(40);
        request.put("offlineDetails", Json.object("startDate", start.toString(),
                "endDate", start.plusDays(1).toString(), "studentLimit", 10));
        CourseRef course = createCourse(tutorToken, request);
        Instant[] when = slot(14, 9, 11);

        JsonNode booking = api.post("/api/portal/room-bookings").bearer(tutorToken)
                .json(Json.object("roomId", room, "offlineCourseId", course.id(),
                        "startsAt", when[0].toString(), "endsAt", when[1].toString()))
                .send().expectStatus(201).data();
        assertThat(booking.path("offlineCourseId").asText()).isEqualTo(course.id().toString());
        assertThat(booking.path("offlineCourseTitle").asText()).isEqualTo(course.title());

        api.post("/api/portal/room-bookings").bearer(login(newApprovedTutor("other").email()))
                .json(Json.object("roomId", room, "offlineCourseId", course.id(),
                        "startsAt", when[0].toString(), "endsAt", when[1].toString()))
                .send().expectError(403, "NOT_COURSE_TUTOR");
    }

    // ── helpers ─────────────────────────────────────────────────────────

    private UUID newRoom(String adminToken, String status, String hourlyRate) {
        UUID a = uploadMedia(adminToken, "room-a.png", "image/png", TestFiles.png());
        UUID b = uploadMedia(adminToken, "room-b.png", "image/png", TestFiles.png());
        return UUID.fromString(api.post("/api/admin/rooms").bearer(adminToken)
                .json(Json.object("name", "Room " + word(), "roomNumber", word(), "building", "IT-test",
                        "capacity", 20, "status", status, "hourlyRate", hourlyRate, "currency", "AZN",
                        "imageMediaIds", List.of(a, b)))
                .send().expectStatus(201).data().path("id").asText());
    }

    private ApiClient.Response book(String tutorToken, UUID room, Instant[] when) {
        return api.post("/api/portal/room-bookings").bearer(tutorToken)
                .json(Json.object("roomId", room, "startsAt", when[0].toString(), "endsAt", when[1].toString()))
                .send();
    }

    private ApiClient.Response decide(String adminToken, String bookingId, String decision) {
        return api.post("/api/portal/room-bookings/admin/" + bookingId + "/decision").bearer(adminToken)
                .json(Json.object("decision", decision, "note", "integration test")).send();
    }

    /** A slot {@code daysAhead} days from now, from one hour to another, Baku time. */
    private static Instant[] slot(int daysAhead, int fromHour, int toHour) {
        return at(LocalDate.now(BAKU).plusDays(daysAhead), fromHour, toHour);
    }

    private static Instant[] at(LocalDate day, int fromHour, int toHour) {
        return new Instant[]{
                day.atTime(LocalTime.of(fromHour, 0)).atZone(BAKU).toInstant(),
                day.atTime(LocalTime.of(toHour, 0)).atZone(BAKU).toInstant()};
    }
}
