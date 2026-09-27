package com.eduplatform.eduplatform_backend.enrollment;

import com.eduplatform.eduplatform_backend.support.AbstractIntegrationTest;
import com.eduplatform.eduplatform_backend.support.ApiClient;
import com.eduplatform.eduplatform_backend.support.Json;
import com.eduplatform.eduplatform_backend.support.TestFiles;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What a place on a course grants, and that taking it away takes everything with it. Removing a
 * participant used to revoke nothing: the CANCELLED enrolment still streamed lesson files,
 * recorded progress — which at 100% set it COMPLETED, back on the roster — and posted reviews.
 */
class ParticipantPlaceTest extends AbstractIntegrationTest {

    @Test
    void aRemovedParticipantLosesTheFilesTheProgressAndTheReview() {
        String tutorToken = login(newApprovedTutor("tutor").email());
        CourseRef course = createCourse(tutorToken, courseRequest("revoked", true));
        UUID pdf = uploadMedia(tutorToken, "notes.pdf", "application/pdf", TestFiles.pdf());
        UUID module = id(api.post("/api/portal/courses/" + course.id() + "/modules").bearer(tutorToken)
                .json(Json.object("title", "Module", "orderIndex", 0)).send().expectStatus(201));
        UUID lesson = id(api.post("/api/portal/modules/" + module + "/lessons").bearer(tutorToken)
                .json(Json.object("title", "Notes", "contentType", "PDF", "videoMediaId", pdf,
                        "durationSeconds", 60, "orderIndex", 0, "preview", false))
                .send().expectStatus(201));
        publish(tutorToken, course.id());
        TestUser participant = newUser("participant", "USER");
        String token = login(participant.email());
        String adminToken = newAdminToken();
        api.post("/api/admin/courses/" + course.id() + "/participants").bearer(adminToken)
                .json(Json.object("userId", participant.id())).send().expectStatus(201);
        api.get("/api/media/" + pdf + "/content").bearer(token).send().expectStatus(200);

        api.delete("/api/admin/courses/" + course.id() + "/participants/" + participant.id()).bearer(adminToken)
                .send().expectStatus(204);

        api.get("/api/media/" + pdf + "/content").bearer(token).send().expectError(403, "MEDIA_FORBIDDEN");
        progress(token, course.id(), lesson).expectError(403, "NOT_ENROLLED");
        api.get("/api/portal/enrollments/courses/" + course.id() + "/progress").bearer(token).send()
                .expectError(403, "NOT_ENROLLED");
        api.post("/api/portal/courses/" + course.id() + "/reviews").bearer(token)
                .json(Json.object("rating", 1, "title", "Revenge", "body", "Removed and still reviewing"))
                .send().expectError(403, "NOT_ENROLLED");
        api.post("/api/portal/enrollments/courses/" + course.id() + "/free").bearer(token).send()
                .expectError(403, "ENROLLMENT_REVOKED");

        assertThat(jdbc.queryForObject("select status from enrollments where user_id = ? and course_id = ?",
                String.class, participant.id(), course.id())).isEqualTo("CANCELLED");
        assertThat(jdbc.queryForObject("select enrolled_count from courses where id = ?", Integer.class, course.id()))
                .isZero();
    }

    /**
     * Completions of lessons deleted since used to be counted against the live total, which went
     * past 100%, hit the column's CHECK, and failed every later progress save with 409.
     */
    @Test
    void progressStillSavesAfterACompletedLessonIsDeleted() {
        String tutorToken = login(newApprovedTutor("tutor").email());
        CourseRef course = createCourse(tutorToken, courseRequest("shrinking", true));
        UUID module = id(api.post("/api/portal/courses/" + course.id() + "/modules").bearer(tutorToken)
                .json(Json.object("title", "Module", "orderIndex", 0)).send().expectStatus(201));
        UUID first = textLesson(tutorToken, module);
        UUID second = textLesson(tutorToken, module);
        publish(tutorToken, course.id());
        String token = login(newUser("learner", "USER").email());
        api.post("/api/portal/enrollments/courses/" + course.id() + "/free").bearer(token).send().expectStatus(201);

        progress(token, course.id(), second).expectStatus(200);
        api.delete("/api/portal/lessons/" + second).bearer(tutorToken).send().expectStatus(204);

        progress(token, course.id(), first).expectStatus(200);
        assertThat(jdbc.queryForObject("""
                select progress_percent from enrollments e join users u on u.id = e.user_id
                where e.course_id = ?
                """, Integer.class, course.id())).isEqualTo(100);
    }

    /** A 20-seat in-person training used to accept any number of self-enrolments. */
    @Test
    void anInPersonCourseStopsAtItsSeatLimit() {
        String tutorToken = login(newApprovedTutor("tutor").email());
        Map<String, Object> request = courseRequest("seats", true);
        request.put("courseType", "OFFLINE");
        request.remove("onlineDetails");
        LocalDate start = LocalDate.now().plusDays(20);
        request.put("offlineDetails", Json.object("startDate", start.toString(),
                "endDate", start.plusDays(2).toString(), "studentLimit", 1, "city", "Baku"));
        CourseRef course = createCourse(tutorToken, request);
        publish(tutorToken, course.id());

        api.post("/api/portal/enrollments/courses/" + course.id() + "/free")
                .bearer(login(newUser("first", "USER").email())).send().expectStatus(201);
        api.post("/api/portal/enrollments/courses/" + course.id() + "/free")
                .bearer(login(newUser("second", "USER").email())).send().expectError(409, "COURSE_FULL");

        assertThat(jdbc.queryForObject("select enrolled_count from offline_course_details where course_id = ?",
                Integer.class, course.id())).isEqualTo(1);
    }

    private ApiClient.Response progress(String token, UUID courseId, UUID lessonId) {
        return api.put("/api/portal/enrollments/courses/" + courseId + "/lessons/" + lessonId + "/progress")
                .bearer(token).json(Json.object("status", "COMPLETED", "positionSec", 60)).send();
    }

    private UUID textLesson(String token, UUID module) {
        return id(api.post("/api/portal/modules/" + module + "/lessons").bearer(token)
                .json(Json.object("title", "Lesson " + word(), "contentType", "TEXT", "durationSeconds", 60,
                        "orderIndex", 0, "preview", false))
                .send().expectStatus(201));
    }

    private static UUID id(ApiClient.Response created) {
        return UUID.fromString(created.data().path("id").asText());
    }
}
