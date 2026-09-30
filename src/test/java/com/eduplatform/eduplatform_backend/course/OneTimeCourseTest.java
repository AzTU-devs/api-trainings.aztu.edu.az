package com.eduplatform.eduplatform_backend.course;

import com.eduplatform.eduplatform_backend.support.AbstractIntegrationTest;
import com.eduplatform.eduplatform_backend.support.ApiClient;
import com.eduplatform.eduplatform_backend.support.Json;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;

import static com.eduplatform.eduplatform_backend.support.Json.field;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * ONE_TIME courses (V22): an in-person training held once, on one date between two times. Its
 * schedule lives in the same offline details an OFFLINE course uses, and the server owns the parts
 * that follow from the rest — the end date, the weekly hours and the total hours — so no client can
 * store a schedule that contradicts itself. Everything in-person applies to it as to OFFLINE: seats,
 * the catalogue's duration, and the exemption from the online lesson rule.
 */
class OneTimeCourseTest extends AbstractIntegrationTest {

    private static final LocalDate DAY = LocalDate.now().plusDays(40);

    @Test
    void aOneTimeCourseKeepsItsDayAndTimesAndTheServerWorksOutItsHours() {
        String token = login(newApprovedTutor("one-time").email());
        Map<String, Object> request = oneTimeRequest(10);
        details(request).put("weeklyHours", 12);
        details(request).put("totalHours", 99);

        JsonNode course = api.post("/api/portal/courses").bearer(token).json(request).send().expectStatus(201).data();

        assertThat(course.path("courseType").asText()).isEqualTo("ONE_TIME");
        JsonNode offline = course.path("offlineDetails");
        assertThat(offline.path("startDate").asText()).isEqualTo(DAY.toString());
        assertThat(offline.path("endDate").asText()).as("one day").isEqualTo(DAY.toString());
        assertThat(offline.path("startTime").asText()).startsWith("10:00");
        assertThat(offline.path("endTime").asText()).startsWith("13:30");
        assertThat(offline.path("weeklyHours").isNull()).as("weeklyHours in %s", offline).isTrue();
        assertThat(offline.path("totalHours").decimalValue()).isEqualByComparingTo("3.5");
        assertThat(course.path("onlineDetails").isNull()).isTrue();
    }

    @Test
    void theCreateRulesAnswerWithTheirOwnCodes() {
        String token = login(newApprovedTutor("one-time-rules").email());

        Map<String, Object> noDetails = oneTimeRequest(10);
        noDetails.remove("offlineDetails");
        create(token, noDetails).expectError(400, "OFFLINE_DETAILS_REQUIRED");

        Map<String, Object> online = oneTimeRequest(10);
        online.put("onlineDetails", Json.object("totalVideoSeconds", 60));
        create(token, online).expectError(400, "INVALID_DETAILS");

        Map<String, Object> twoDays = oneTimeRequest(10);
        details(twoDays).put("endDate", DAY.plusDays(1).toString());
        create(token, twoDays).expectError(400, "INVALID_DATE_RANGE");

        Map<String, Object> noTimes = oneTimeRequest(10);
        details(noTimes).remove("endTime");
        create(token, noTimes).expectError(400, "ONE_TIME_TIME_REQUIRED");

        Map<String, Object> backwards = oneTimeRequest(10);
        details(backwards).put("endTime", "09:00");
        create(token, backwards).expectError(400, "INVALID_TIME_RANGE");

        Map<String, Object> noSeats = oneTimeRequest(10);
        details(noSeats).put("studentLimit", 0);
        create(token, noSeats).expectError(400, "INVALID_STUDENT_LIMIT");

        // endDate may be sent, as long as it is the same day.
        Map<String, Object> sameDay = oneTimeRequest(10);
        details(sameDay).put("endDate", DAY.toString());
        create(token, sameDay).expectStatus(201);
    }

    /** Re-checked after every partial update, and the derived parts follow the edit. */
    @Test
    void anEditKeepsItOneDayAndRederivesItsHours() {
        String token = login(newApprovedTutor("one-time-edit").email());
        UUID id = UUID.fromString(create(token, oneTimeRequest(10)).expectStatus(201).data().path("id").asText());

        JsonNode moved = edit(token, id, Json.object("startDate", DAY.plusDays(3).toString(),
                "startTime", "09:00:00", "endTime", "17:15")).expectStatus(200).data().path("offlineDetails");
        assertThat(moved.path("startDate").asText()).isEqualTo(DAY.plusDays(3).toString());
        assertThat(moved.path("endDate").asText()).isEqualTo(DAY.plusDays(3).toString());
        assertThat(moved.path("totalHours").decimalValue()).isEqualByComparingTo("8.3");

        edit(token, id, Json.object("endDate", DAY.plusDays(4).toString())).expectError(400, "INVALID_DATE_RANGE");
        edit(token, id, Json.object("endTime", "08:00")).expectError(400, "INVALID_TIME_RANGE");
        edit(token, id, Json.object("studentLimit", 0)).expectError(400, "INVALID_STUDENT_LIMIT");
        api.patch("/api/portal/courses/" + id).bearer(token)
                .json(Json.object("onlineDetails", Json.object("totalVideoSeconds", 60)))
                .send().expectError(400, "INVALID_DETAILS");

        JsonNode untouched = api.patch("/api/portal/courses/" + id).bearer(token)
                .json(Json.object("subtitle", "Only the subtitle", "offlineDetails", Json.object("weeklyHours", 5)))
                .send().expectStatus(200).data().path("offlineDetails");
        assertThat(untouched.path("weeklyHours").isNull()).isTrue();
        assertThat(untouched.path("totalHours").decimalValue()).isEqualByComparingTo("8.3");
        assertThat(jdbc.queryForObject("select end_date = start_date from offline_course_details where course_id = ?",
                Boolean.class, id)).isTrue();
    }

    /** OFFLINE may now record times too; when both are there the end must come after the start. */
    @Test
    void anOfflineCourseAcceptsTimesInOrderOnly() {
        String token = login(newApprovedTutor("offline-times").email());
        Map<String, Object> request = courseRequest("offline-times", true);
        request.put("courseType", "OFFLINE");
        request.remove("onlineDetails");
        request.put("offlineDetails", Json.object("startDate", DAY.toString(), "endDate", DAY.plusDays(5).toString(),
                "startTime", "18:00", "endTime", "17:00", "studentLimit", 10));
        create(token, request).expectError(400, "INVALID_TIME_RANGE");

        details(request).put("endTime", "20:00");
        JsonNode offline = create(token, request).expectStatus(201).data().path("offlineDetails");
        assertThat(offline.path("startTime").asText()).startsWith("18:00");
        assertThat(offline.path("endDate").asText()).isEqualTo(DAY.plusDays(5).toString());
    }

    /**
     * In person everywhere OFFLINE is: publishable without lessons, filtered by its own type, sized
     * by its hours in the catalogue, and holding a limited number of seats.
     */
    @Test
    void itIsInPersonEverywhere() {
        String tutorToken = login(newApprovedTutor("one-time-live").email());
        Map<String, Object> request = oneTimeRequest(1);
        String title = (String) request.get("title");
        UUID id = UUID.fromString(create(tutorToken, request).expectStatus(201).data().path("id").asText());
        api.post("/api/portal/courses/" + id + "/submit").bearer(tutorToken).send().expectStatus(200);
        api.post("/api/admin/courses/" + id + "/decision").bearer(newAdminToken())
                .json(Json.object("decision", "APPROVED")).send().expectStatus(200);

        JsonNode oneTime = api.get("/api/public/courses").query("type", "ONE_TIME").query("q", title)
                .send().expectStatus(200).data();
        assertThat(field(oneTime, "id")).containsExactly(id.toString());
        assertThat(field(oneTime, "totalDurationSec")).containsExactly("12600");
        assertThat(field(api.get("/api/public/courses").query("type", "OFFLINE").query("q", title)
                .send().expectStatus(200).data(), "id")).isEmpty();
        // 3.5 hours falls in the 2-to-6-hour bucket.
        assertThat(field(api.get("/api/public/courses").query("durationBucket", "2to6").query("q", title)
                .send().expectStatus(200).data(), "id")).containsExactly(id.toString());

        api.post("/api/portal/enrollments/courses/" + id + "/free").bearer(login(newUser("first", "USER").email()))
                .send().expectStatus(201);
        api.post("/api/portal/enrollments/courses/" + id + "/free").bearer(login(newUser("second", "USER").email()))
                .send().expectError(409, "COURSE_FULL");
        assertThat(jdbc.queryForObject("select enrolled_count from offline_course_details where course_id = ?",
                Integer.class, id)).isEqualTo(1);
    }

    // ── helpers ─────────────────────────────────────────────────────────

    /** 10:00 to 13:30 on {@link #DAY}: 3.5 hours. */
    private static Map<String, Object> oneTimeRequest(int seats) {
        Map<String, Object> request = courseRequest("one-time", true);
        request.put("courseType", "ONE_TIME");
        request.remove("onlineDetails");
        request.put("offlineDetails", Json.object("startDate", DAY.toString(), "startTime", "10:00",
                "endTime", "13:30", "studentLimit", seats, "city", "Bakı", "addressLine", "H. Cavid pr. 25"));
        return request;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> details(Map<String, Object> request) {
        return (Map<String, Object>) request.get("offlineDetails");
    }

    private ApiClient.Response create(String token, Map<String, Object> request) {
        return api.post("/api/portal/courses").bearer(token).json(request).send();
    }

    private ApiClient.Response edit(String token, UUID id, Map<String, Object> offline) {
        return api.patch("/api/portal/courses/" + id).bearer(token).json(Json.object("offlineDetails", offline)).send();
    }
}
