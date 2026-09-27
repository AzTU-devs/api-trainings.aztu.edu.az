package com.eduplatform.eduplatform_backend.course;

import com.eduplatform.eduplatform_backend.support.AbstractIntegrationTest;
import com.eduplatform.eduplatform_backend.support.ApiClient;
import com.eduplatform.eduplatform_backend.support.Json;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Values a course and its content used to take that meant nothing or did harm: negative or
 * overflowing in-person hours, lesson links that are not web addresses, and positions that collide.
 */
class CourseInputBoundsTest extends AbstractIntegrationTest {

    private static final String REPAIR = "V21__deleted_accounts_and_input_rules.sql";

    /**
     * Negative hours were stored and shown on the public course page; a value past the column's
     * precision came back as a generic "cannot be stored" instead of naming the field.
     */
    @Test
    @SuppressWarnings("unchecked")
    void inPersonHoursAreBounded() {
        String token = login(newApprovedTutor("hours").email());
        Map<String, Object> request = offlineRequest();
        ((Map<String, Object>) request.get("offlineDetails")).put("weeklyHours", -5);
        api.post("/api/portal/courses").bearer(token).json(request).send().expectError(400, "VALIDATION_FAILED");

        CourseRef course = createCourse(token, offlineRequest());
        editOffline(token, course.id(), Json.object("weeklyHours", -5)).expectError(400, "VALIDATION_FAILED");
        editOffline(token, course.id(), Json.object("totalHours", -3)).expectError(400, "VALIDATION_FAILED");
        editOffline(token, course.id(), Json.object("weeklyHours", 1000)).expectError(400, "VALIDATION_FAILED");
        editOffline(token, course.id(), Json.object("totalHours", 1000000)).expectError(400, "VALIDATION_FAILED");

        JsonNode saved = editOffline(token, course.id(), Json.object("weeklyHours", 6, "totalHours", 36))
                .expectStatus(200).data();
        assertThat(saved.path("offlineDetails").path("weeklyHours").decimalValue()).isEqualByComparingTo("6");
    }

    /** Only the length of a lesson link was checked, so javascript: links were served on preview lessons. */
    @Test
    void aLessonLinkMustBeAWebAddress() {
        String token = login(newApprovedTutor("links").email());
        CourseRef course = createCourse(token, courseRequest("links", true));
        UUID module = module(token, course.id());

        lesson(token, module, "javascript:alert(document.cookie)").expectError(400, "VALIDATION_FAILED");
        lesson(token, module, "data:text/html,<script>alert(1)</script>").expectError(400, "VALIDATION_FAILED");
        lesson(token, module, "https://user:pw@meet.example.com/x").expectError(400, "VALIDATION_FAILED");

        UUID lesson = UUID.fromString(lesson(token, module, "  https://meet.example.com/abc-defg  ")
                .expectStatus(201).data().path("id").asText());
        assertThat(jdbc.queryForObject("select video_url from lessons where id = ?", String.class, lesson))
                .isEqualTo("https://meet.example.com/abc-defg");
        api.put("/api/portal/lessons/" + lesson).bearer(token)
                .json(lessonBody("javascript:alert(1)", 0)).send().expectError(400, "VALIDATION_FAILED");
        api.put("/api/portal/lessons/" + lesson).bearer(token)
                .json(lessonBody("", 0)).send().expectStatus(200);
        assertThat(jdbc.queryForObject("select video_url from lessons where id = ?", String.class, lesson)).isNull();
    }

    /**
     * Moving a module or a lesson onto an occupied position hit the partial unique index and came
     * back as the generic 409 CONSTRAINT_VIOLATION.
     */
    @Test
    void anOccupiedPositionIsItsOwnConflict() {
        String token = login(newApprovedTutor("order").email());
        CourseRef course = createCourse(token, courseRequest("order", true));
        UUID first = module(token, course.id());
        UUID second = module(token, course.id());
        UUID lessonA = UUID.fromString(lesson(token, first, null).expectStatus(201).data().path("id").asText());
        UUID lessonB = UUID.fromString(lesson(token, first, null).expectStatus(201).data().path("id").asText());

        api.put("/api/portal/modules/" + second).bearer(token)
                .json(Json.object("title", "Second", "orderIndex", 0)).send().expectError(409, "ORDER_INDEX_TAKEN");
        api.put("/api/portal/lessons/" + lessonB).bearer(token)
                .json(lessonBody(null, 0)).send().expectError(409, "ORDER_INDEX_TAKEN");

        // Its own position, and a free one, are fine; that is how two are swapped.
        api.put("/api/portal/modules/" + second).bearer(token)
                .json(Json.object("title", "Second", "orderIndex", 1)).send().expectStatus(200);
        api.put("/api/portal/lessons/" + lessonA).bearer(token).json(lessonBody(null, 7)).send().expectStatus(200);
        api.put("/api/portal/lessons/" + lessonB).bearer(token).json(lessonBody(null, 0)).send().expectStatus(200);
    }

    /** The rows the old rules let in are cleared by V21, which then holds the table to the hours rule. */
    @Test
    void theRepairClearsHoursAndLinksTheOldRulesLetIn() {
        String token = login(newApprovedTutor("legacy").email());
        CourseRef offline = createCourse(token, offlineRequest());
        CourseRef online = createCourse(token, courseRequest("legacy-links", true));
        UUID module = module(token, online.id());
        UUID script = UUID.fromString(lesson(token, module, null).expectStatus(201).data().path("id").asText());
        UUID bare = UUID.fromString(lesson(token, module, null).expectStatus(201).data().path("id").asText());
        jdbc.update("alter table offline_course_details drop constraint if exists chk_offline_hours");
        jdbc.update("update offline_course_details set weekly_hours = -5, total_hours = -3 where course_id = ?",
                offline.id());
        jdbc.update("update lessons set video_url = 'javascript:alert(1)' where id = ?", script);
        jdbc.update("update lessons set video_url = ' meet.google.com/abc-defg ' where id = ?", bare);

        rerunMigration(REPAIR);

        assertThat(jdbc.queryForMap("select weekly_hours, total_hours from offline_course_details where course_id = ?",
                offline.id())).containsEntry("weekly_hours", null).containsEntry("total_hours", null);
        assertThat(jdbc.queryForObject("select video_url from lessons where id = ?", String.class, script)).isNull();
        assertThat(jdbc.queryForObject("select video_url from lessons where id = ?", String.class, bare))
                .isEqualTo("https://meet.google.com/abc-defg");
        editOffline(token, offline.id(), Json.object("city", "Ganja")).expectStatus(200);
        assertThat(jdbc.queryForObject(
                "select count(*) from pg_constraint where conname = 'chk_offline_hours'", Integer.class)).isEqualTo(1);
    }

    private static Map<String, Object> offlineRequest() {
        Map<String, Object> request = courseRequest("hours", true);
        request.put("courseType", "OFFLINE");
        request.remove("onlineDetails");
        LocalDate start = LocalDate.now().plusDays(30);
        request.put("offlineDetails", Json.object("startDate", start.toString(),
                "endDate", start.plusDays(5).toString(), "studentLimit", 10, "city", "Baku"));
        return request;
    }

    private ApiClient.Response editOffline(String token, UUID courseId, Map<String, Object> offline) {
        return api.patch("/api/portal/courses/" + courseId).bearer(token)
                .json(Json.object("offlineDetails", offline)).send();
    }

    private UUID module(String token, UUID courseId) {
        return UUID.fromString(api.post("/api/portal/courses/" + courseId + "/modules").bearer(token)
                .json(Json.object("title", "Module " + word(), "orderIndex", 0)).send().expectStatus(201)
                .data().path("id").asText());
    }

    private ApiClient.Response lesson(String token, UUID moduleId, String videoUrl) {
        return api.post("/api/portal/modules/" + moduleId + "/lessons").bearer(token)
                .json(lessonBody(videoUrl, 0)).send();
    }

    private static Map<String, Object> lessonBody(String videoUrl, int orderIndex) {
        return Json.object("title", "Live session", "description", "Integration test lesson",
                "contentType", "LIVE_SESSION", "videoMediaId", null, "videoUrl", videoUrl,
                "durationSeconds", 60, "orderIndex", orderIndex, "preview", true);
    }
}
