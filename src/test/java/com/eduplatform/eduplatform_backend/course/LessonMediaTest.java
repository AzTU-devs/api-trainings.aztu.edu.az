package com.eduplatform.eduplatform_backend.course;

import com.eduplatform.eduplatform_backend.support.AbstractIntegrationTest;
import com.eduplatform.eduplatform_backend.support.ApiClient;
import com.eduplatform.eduplatform_backend.support.Json;
import com.eduplatform.eduplatform_backend.support.TestFiles;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A lesson's videoMediaId goes through the same checks as a course's thumbnail and trailer: the
 * media must exist (404 MEDIA_NOT_FOUND), belong to the tutor (403 MEDIA_FORBIDDEN), be READY and
 * be of the kind the lesson delivers, a video for a VIDEO lesson and a PDF for a PDF lesson
 * (422 INVALID_MEDIA_FOR_FIELD).
 *
 * <p>Any id used to be accepted, and the media endpoint streams a lesson's file to everyone
 * enrolled in the course, so a tutor could publish another user's private upload by naming its id
 * in a lesson of their own free course and enrolling in it.
 */
class LessonMediaTest extends AbstractIntegrationTest {

    @Test
    void anotherUsersPrivateUploadCannotBeALessonVideo() {
        UUID someoneElses = uploadMedia(login(newApprovedTutor("owner").email()), "private.mp4", "video/mp4",
                TestFiles.mp4());
        String token = login(newApprovedTutor("tutor").email());
        UUID module = moduleOf(token, createCourse(token, courseRequest("borrowed-video", true)));

        addLesson(token, module, lesson("VIDEO", someoneElses)).expectError(403, "MEDIA_FORBIDDEN");

        assertThat(lessonsUsing(someoneElses)).as("lessons pointing at the other user's upload").isZero();
    }

    /** A full PUT replaces the file, so an existing lesson is no back door. */
    @Test
    void anotherUsersPrivateUploadCannotReplaceAnExistingLessonVideo() {
        UUID someoneElses = uploadMedia(login(newUser("student", "USER").email()), "private.mp4", "video/mp4",
                TestFiles.mp4());
        String token = login(newApprovedTutor("tutor").email());
        UUID own = uploadMedia(token, "lesson.mp4", "video/mp4", TestFiles.mp4());
        UUID module = moduleOf(token, createCourse(token, courseRequest("swapped-video", true)));
        UUID existing = id(addLesson(token, module, lesson("VIDEO", own)).expectStatus(201).data());

        api.put("/api/portal/lessons/" + existing).bearer(token).json(lesson("VIDEO", someoneElses))
                .send().expectError(403, "MEDIA_FORBIDDEN");

        assertThat(videoInDb(existing)).isEqualTo(own);
    }

    @Test
    void aPdfCannotBeALessonVideo() {
        String token = login(newApprovedTutor("tutor").email());
        UUID pdf = uploadMedia(token, "notes.pdf", "application/pdf", TestFiles.pdf());
        UUID module = moduleOf(token, createCourse(token, courseRequest("pdf-video", true)));

        addLesson(token, module, lesson("VIDEO", pdf)).expectError(422, "INVALID_MEDIA_FOR_FIELD");

        assertThat(lessonsUsing(pdf)).as("lessons pointing at the PDF").isZero();
    }

    @Test
    void theTutorsOwnUploadedMp4IsAcceptedAsTheLessonVideo() {
        String token = login(newApprovedTutor("tutor").email());
        UUID mp4 = uploadMedia(token, "lesson.mp4", "video/mp4", TestFiles.mp4());
        UUID module = moduleOf(token, createCourse(token, courseRequest("own-video", true)));

        JsonNode created = addLesson(token, module, lesson("VIDEO", mp4)).expectStatus(201).data();

        assertThat(created.path("videoMediaId").asText()).isEqualTo(mp4.toString());
        assertThat(videoInDb(id(created))).isEqualTo(mp4);
    }

    /** The field carries whatever the lesson delivers, so the kind follows the content type. */
    @Test
    void aPdfLessonTakesTheTutorsOwnPdfButNotAVideo() {
        String token = login(newApprovedTutor("tutor").email());
        UUID pdf = uploadMedia(token, "handout.pdf", "application/pdf", TestFiles.pdf());
        UUID mp4 = uploadMedia(token, "lesson.mp4", "video/mp4", TestFiles.mp4());
        UUID module = moduleOf(token, createCourse(token, courseRequest("pdf-lesson", true)));

        addLesson(token, module, lesson("PDF", mp4)).expectError(422, "INVALID_MEDIA_FOR_FIELD");
        UUID created = id(addLesson(token, module, lesson("PDF", pdf)).expectStatus(201).data());

        assertThat(videoInDb(created)).isEqualTo(pdf);
    }

    /** The answer a course field gives too; lessons used to have a 400 INVALID_MEDIA of their own. */
    @Test
    void anUnknownMediaIdIsNotFound() {
        String token = login(newApprovedTutor("tutor").email());
        UUID module = moduleOf(token, createCourse(token, courseRequest("phantom-video", true)));

        addLesson(token, module, lesson("VIDEO", UUID.randomUUID())).expectError(404, "MEDIA_NOT_FOUND");
    }

    private UUID moduleOf(String token, CourseRef course) {
        return id(api.post("/api/portal/courses/" + course.id() + "/modules").bearer(token)
                .json(Json.object("title", "Module one", "description", "Integration test module", "orderIndex", 0))
                .send().expectStatus(201).data());
    }

    private ApiClient.Response addLesson(String token, UUID module, Map<String, Object> lesson) {
        return api.post("/api/portal/modules/" + module + "/lessons").bearer(token).json(lesson).send();
    }

    /** A lesson body; every test adds at most one successful lesson per module, so order 0 never collides. */
    private static Map<String, Object> lesson(String contentType, UUID mediaId) {
        return Json.object(
                "title", "Lesson " + word(),
                "description", "Integration test lesson",
                "contentType", contentType,
                "videoMediaId", mediaId,
                "videoUrl", null,
                "durationSeconds", 60,
                "orderIndex", 0,
                "preview", false);
    }

    private static UUID id(JsonNode entity) {
        return UUID.fromString(entity.path("id").asText());
    }

    private UUID videoInDb(UUID lessonId) {
        return jdbc.queryForObject("select video_media_id from lessons where id = ?", UUID.class, lessonId);
    }

    private int lessonsUsing(UUID media) {
        return jdbc.queryForObject("select count(*) from lessons where video_media_id = ?", Integer.class, media);
    }
}
