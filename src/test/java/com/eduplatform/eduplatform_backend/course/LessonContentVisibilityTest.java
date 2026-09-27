package com.eduplatform.eduplatform_backend.course;

import com.eduplatform.eduplatform_backend.support.AbstractIntegrationTest;
import com.eduplatform.eduplatform_backend.support.Json;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What the public course page gives away, and to whom. It used to hand every anonymous caller
 * every lesson's text, meeting link and file id, paid courses included, and it answered 404 to
 * a participant as soon as an admin took their course back to DRAFT.
 */
class LessonContentVisibilityTest extends AbstractIntegrationTest {

    @Test
    void visitorsSeeTheOutlineButOnlyPreviewLessonsContent() {
        Fixture f = publishedCourseWithTwoLessons();

        JsonNode anonymous = api.get("/api/public/courses/" + f.course.slug()).send().expectStatus(200).data();
        JsonNode locked = lesson(anonymous, f.lockedLesson);
        assertThat(locked.path("title").asText()).as("the outline stays").isNotBlank();
        assertThat(locked.path("videoUrl").isNull()).isTrue();
        assertThat(locked.path("description").isNull()).isTrue();
        JsonNode preview = lesson(anonymous, f.previewLesson);
        assertThat(preview.path("videoUrl").asText()).isEqualTo("https://meet.example/preview");

        String stranger = login(newUser("stranger", "USER").email());
        assertThat(lesson(api.get("/api/public/courses/" + f.course.slug()).bearer(stranger).send()
                .expectStatus(200).data(), f.lockedLesson).path("videoUrl").isNull()).isTrue();
    }

    @Test
    void aParticipantAndTheTutorSeeEveryLesson() {
        Fixture f = publishedCourseWithTwoLessons();
        String participant = login(newUser("participant", "USER").email());
        api.post("/api/portal/enrollments/courses/" + f.course.id() + "/free").bearer(participant).send()
                .expectStatus(201);

        assertThat(lesson(api.get("/api/public/courses/" + f.course.slug()).bearer(participant).send()
                .expectStatus(200).data(), f.lockedLesson).path("videoUrl").asText())
                .isEqualTo("https://meet.example/secret");
        assertThat(lesson(api.get("/api/public/courses/" + f.course.slug()).bearer(f.tutorToken).send()
                .expectStatus(200).data(), f.lockedLesson).path("videoUrl").asText())
                .isEqualTo("https://meet.example/secret");
    }

    /** Taking a course back to DRAFT for an edit must not lock its participants out. */
    @Test
    void anUnpublishedCourseStaysOpenToThoseWhoHoldAPlace() {
        Fixture f = publishedCourseWithTwoLessons();
        TestUser participant = newUser("kept", "USER");
        TestUser removed = newUser("removed", "USER");
        String adminToken = newAdminToken();
        for (TestUser u : new TestUser[]{participant, removed}) {
            api.post("/api/admin/courses/" + f.course.id() + "/participants").bearer(adminToken)
                    .json(Json.object("userId", u.id())).send().expectStatus(201);
        }
        api.delete("/api/admin/courses/" + f.course.id() + "/participants/" + removed.id()).bearer(adminToken)
                .send().expectStatus(204);

        api.post("/api/admin/courses/" + f.course.id() + "/unpublish").bearer(adminToken).send().expectStatus(200);

        api.get("/api/public/courses/" + f.course.slug()).send().expectError(404, "COURSE_NOT_FOUND");
        api.get("/api/public/courses/" + f.course.slug()).bearer(login(participant.email())).send()
                .expectStatus(200);
        api.get("/api/public/courses/" + f.course.slug()).bearer(login(removed.email())).send()
                .expectError(404, "COURSE_NOT_FOUND");
    }

    /** The moderator's note reaches the tutor, and nobody else. */
    @Test
    void theRejectionNoteIsShownToTheTutorOnly() {
        String tutorToken = login(newApprovedTutor("tutor").email());
        CourseRef course = createCourse(tutorToken, courseRequest("sent-back", true));
        submitForReview(tutorToken, course.id());
        api.post("/api/admin/courses/" + course.id() + "/decision").bearer(newAdminToken())
                .json(Json.object("decision", "REJECTED", "note", "Add a syllabus")).send().expectStatus(200);

        JsonNode mine = api.get("/api/public/courses/" + course.slug()).bearer(tutorToken).send()
                .expectStatus(200).data();
        assertThat(mine.path("rejectionReason").asText()).isEqualTo("Add a syllabus");
        assertThat(mine.path("submittedAt").isNull()).as("submittedAt is recorded").isFalse();
        JsonNode listed = api.get("/api/portal/courses").bearer(tutorToken).send().expectStatus(200).data();
        assertThat(Json.find(listed, "id", course.id().toString()).path("rejectionReason").asText())
                .isEqualTo("Add a syllabus");
    }

    // ── helpers ─────────────────────────────────────────────────────────

    private record Fixture(CourseRef course, String tutorToken, UUID previewLesson, UUID lockedLesson) {}

    private Fixture publishedCourseWithTwoLessons() {
        String tutorToken = login(newApprovedTutor("tutor").email());
        CourseRef course = createCourse(tutorToken, courseRequest("visibility", true));
        UUID module = UUID.fromString(api.post("/api/portal/courses/" + course.id() + "/modules").bearer(tutorToken)
                .json(Json.object("title", "Module", "orderIndex", 0)).send().expectStatus(201).data()
                .path("id").asText());
        UUID preview = lessonId(tutorToken, module, "https://meet.example/preview", true);
        UUID locked = lessonId(tutorToken, module, "https://meet.example/secret", false);
        publish(tutorToken, course.id());
        return new Fixture(course, tutorToken, preview, locked);
    }

    private UUID lessonId(String token, UUID module, String url, boolean preview) {
        return UUID.fromString(api.post("/api/portal/modules/" + module + "/lessons").bearer(token)
                .json(Json.object("title", "Lesson " + word(), "description", "Lesson text",
                        "contentType", "LIVE_SESSION", "videoUrl", url, "durationSeconds", 3600,
                        "orderIndex", 0, "preview", preview))
                .send().expectStatus(201).data().path("id").asText());
    }

    private static JsonNode lesson(JsonNode course, UUID lessonId) {
        for (JsonNode module : course.path("modules")) {
            for (JsonNode lesson : module.path("lessons")) {
                if (lessonId.toString().equals(lesson.path("id").asText())) return lesson;
            }
        }
        throw new AssertionError("lesson " + lessonId + " not in " + course);
    }
}
