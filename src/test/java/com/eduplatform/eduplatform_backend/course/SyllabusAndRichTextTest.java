package com.eduplatform.eduplatform_backend.course;

import com.eduplatform.eduplatform_backend.support.AbstractIntegrationTest;
import com.eduplatform.eduplatform_backend.support.ApiClient;
import com.eduplatform.eduplatform_backend.support.Json;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The syllabus as a list of items (V22), and the rich-text fields the dashboard's editor now writes
 * as HTML, sanitised on the way in on every path that writes them. The allowlist itself is pinned
 * in RichTextSanitizerTest; this checks it is applied where it has to be.
 */
class SyllabusAndRichTextTest extends AbstractIntegrationTest {

    @Test
    void syllabusItemsAreStoredInOrderWithTheirDescriptionsSanitised() {
        String token = login(newApprovedTutor("syllabus").email());
        Map<String, Object> request = courseRequest("syllabus", true);
        request.put("syllabusItems", List.of(
                Json.object("title", "  Introduction to Python ",
                        "description", "<p>Variables, <strong>types</strong></p><script>alert(1)</script>"),
                Json.object("title", "Control flow", "description", null),
                Json.object("title", "Wrap-up", "description", "Plain text & notes")));

        JsonNode course = createCourseBody(token, request);

        JsonNode items = course.path("syllabusItems");
        assertThat(items.size()).isEqualTo(3);
        assertThat(items.get(0).path("title").asText()).isEqualTo("Introduction to Python");
        assertThat(items.get(0).path("description").asText()).isEqualTo("<p>Variables, <strong>types</strong></p>");
        assertThat(items.get(1).path("description").asText()).as("a missing description is \"\"").isEmpty();
        assertThat(items.get(1).path("description").isTextual()).isTrue();
        assertThat(items.get(2).path("description").asText()).isEqualTo("Plain text & notes");
    }

    @Test
    void anUpdateLeavesClearsOrReplacesTheWholeList() {
        String token = login(newApprovedTutor("syllabus-edit").email());
        Map<String, Object> request = courseRequest("syllabus-edit", true);
        request.put("syllabusItems", List.of(Json.object("title", "One"), Json.object("title", "Two")));
        UUID id = UUID.fromString(createCourseBody(token, request).path("id").asText());

        JsonNode kept = patch(token, id, Json.object("subtitle", "Unrelated")).expectStatus(200).data();
        assertThat(Json.texts(titles(kept))).containsExactly("One", "Two");

        JsonNode replaced = patch(token, id, Json.object("syllabusItems",
                List.of(Json.object("title", "Two"), Json.object("title", "Three")))).expectStatus(200).data();
        assertThat(Json.texts(titles(replaced))).containsExactly("Two", "Three");

        JsonNode cleared = patch(token, id, Json.object("syllabusItems", List.of())).expectStatus(200).data();
        assertThat(cleared.path("syllabusItems").isArray()).isTrue();
        assertThat(cleared.path("syllabusItems").size()).isZero();
        assertThat(jdbc.queryForObject("select syllabus_items::text from courses where id = ?", String.class, id))
                .isEqualTo("[]");
    }

    @Test
    void theListAndItsItemsAreBounded() {
        String token = login(newApprovedTutor("syllabus-bounds").email());
        List<Map<String, Object>> tooMany = new ArrayList<>();
        for (int i = 0; i < 101; i++) tooMany.add(Json.object("title", "Item " + i));
        Map<String, Object> request = courseRequest("syllabus-bounds", true);
        request.put("syllabusItems", tooMany);
        api.post("/api/portal/courses").bearer(token).json(request).send().expectError(400, "VALIDATION_FAILED");

        request.put("syllabusItems", List.of(Json.object("title", "   ")));
        api.post("/api/portal/courses").bearer(token).json(request).send().expectError(400, "VALIDATION_FAILED");
        request.put("syllabusItems", List.of(Json.object("title", "x".repeat(201))));
        api.post("/api/portal/courses").bearer(token).json(request).send().expectError(400, "VALIDATION_FAILED");

        request.put("syllabusItems", tooMany.subList(0, 100));
        assertThat(createCourseBody(token, request).path("syllabusItems").size()).isEqualTo(100);
    }

    /** A visitor sees the syllabus: it is the course's outline, not a participant's lesson content. */
    @Test
    void theSyllabusIsPublicAndAnOldCourseReadsAsAnEmptyList() {
        String token = login(newApprovedTutor("syllabus-public").email());
        Map<String, Object> request = courseRequest("syllabus-public", true);
        request.put("syllabusItems", List.of(Json.object("title", "Week one", "description", "<p>Basics</p>")));
        JsonNode created = createCourseBody(token, request);
        UUID id = UUID.fromString(created.path("id").asText());
        publish(token, id);

        JsonNode anonymous = api.get("/api/public/courses/" + created.path("slug").asText())
                .send().expectStatus(200).data();
        assertThat(anonymous.path("syllabusItems").get(0).path("title").asText()).isEqualTo("Week one");

        JsonNode legacy = createCourseBody(token, courseRequest("no-syllabus", true));
        assertThat(legacy.path("syllabusItems").isArray()).as("always an array: %s", legacy).isTrue();
        assertThat(legacy.path("syllabusItems").size()).isZero();
    }

    @Test
    void theCoursesRichTextIsSanitisedOnEveryWritePath() {
        TestTutor tutor = newApprovedTutor("rich-text");
        String token = login(tutor.email());
        Map<String, Object> request = courseRequest("rich-text", true);
        request.put("description", "<p onclick=\"x()\">Intro <a href=\"javascript:alert(1)\">here</a></p>");
        request.put("requirements", "Plain text\nwith line breaks & symbols");
        // Past the old 5000 limit, which the markup made too tight.
        request.put("learningOutcomes", "<p>" + "a".repeat(6000) + "</p>");
        JsonNode created = createCourseBody(token, request);
        UUID id = UUID.fromString(created.path("id").asText());

        String description = created.path("description").asText();
        assertThat(description).startsWith("<p>Intro <a ").endsWith(">here</a></p>")
                .contains("target=\"_blank\"", "rel=\"noopener noreferrer nofollow\"")
                .doesNotContain("href", "onclick", "javascript");
        assertThat(created.path("requirements").asText()).isEqualTo("Plain text\nwith line breaks & symbols");
        assertThat(created.path("learningOutcomes").asText()).hasSize(6007);

        JsonNode edited = patch(token, id, Json.object("requirements", "<p><img src=x onerror=alert(1)></p>"))
                .expectStatus(200).data();
        assertThat(edited.path("requirements").asText()).as("nothing left but markup").isEmpty();

        // The admin's create and edit go through the same sanitiser.
        Map<String, Object> adminCourse = courseRequest("rich-text-admin", true);
        adminCourse.put("description", "<h2 style=\"text-align:center;color:red\">Title</h2><style>p{}</style>");
        adminCourse.put("syllabusItems", List.of(Json.object("title", "A", "description", "<iframe></iframe><p>ok</p>")));
        JsonNode byAdmin = api.post("/api/admin/courses").bearer(newAdminToken())
                .json(Json.object("course", adminCourse, "tutorIds", Set.of(tutor.profileId()),
                        "authorizedTutorId", tutor.profileId()))
                .send().expectStatus(201).data();
        assertThat(byAdmin.path("description").asText()).isEqualTo("<h2 style=\"text-align: center\">Title</h2>");
        assertThat(byAdmin.path("syllabusItems").get(0).path("description").asText()).isEqualTo("<p>ok</p>");
        JsonNode adminEdit = api.patch("/api/admin/courses/" + byAdmin.path("id").asText()).bearer(newAdminToken())
                .json(Json.object("learningOutcomes", "<ul><li>One</li></ul><script>x</script>"))
                .send().expectStatus(200).data();
        assertThat(adminEdit.path("learningOutcomes").asText()).isEqualTo("<ul><li>One</li></ul>");
    }

    @Test
    void moduleAndLessonDescriptionsAreSanitisedToo() {
        String token = login(newApprovedTutor("rich-content").email());
        UUID courseId = UUID.fromString(createCourseBody(token, courseRequest("rich-content", true)).path("id").asText());

        JsonNode module = api.post("/api/portal/courses/" + courseId + "/modules").bearer(token)
                .json(Json.object("title", "Module", "description", "<p>Module <b>one</b></p><script>1</script>",
                        "orderIndex", 0))
                .send().expectStatus(201).data();
        assertThat(module.path("description").asText()).isEqualTo("<p>Module <b>one</b></p>");
        UUID moduleId = UUID.fromString(module.path("id").asText());
        api.put("/api/portal/modules/" + moduleId).bearer(token)
                .json(Json.object("title", "Module", "description", "x".repeat(9000), "orderIndex", 0))
                .send().expectStatus(200);

        Map<String, Object> lesson = Json.object("title", "Lesson", "description", "<p>Read <em>this</em></p>"
                        + "<img src=x onerror=alert(1)>", "contentType", "TEXT", "videoMediaId", null,
                "videoUrl", null, "durationSeconds", 60, "orderIndex", 0, "preview", true);
        JsonNode created = api.post("/api/portal/modules/" + moduleId + "/lessons").bearer(token).json(lesson)
                .send().expectStatus(201).data();
        assertThat(created.path("description").asText()).isEqualTo("<p>Read <em>this</em></p>");

        lesson.put("description", "<p>" + "b".repeat(15000) + "</p><object data=\"x\"></object>");
        JsonNode updated = api.put("/api/portal/lessons/" + created.path("id").asText()).bearer(token).json(lesson)
                .send().expectStatus(200).data();
        assertThat(updated.path("description").asText()).isEqualTo("<p>" + "b".repeat(15000) + "</p>");
    }

    // ── helpers ─────────────────────────────────────────────────────────

    private JsonNode createCourseBody(String token, Map<String, Object> request) {
        return api.post("/api/portal/courses").bearer(token).json(request).send().expectStatus(201).data();
    }

    private ApiClient.Response patch(String token, UUID id, Map<String, Object> body) {
        return api.patch("/api/portal/courses/" + id).bearer(token).json(body).send();
    }

    /** The items' titles as a JSON array, for Json.texts. */
    private static JsonNode titles(JsonNode course) {
        ArrayNode out = JsonNodeFactory.instance.arrayNode();
        course.path("syllabusItems").forEach(item -> out.add(item.path("title").asText()));
        return out;
    }
}
