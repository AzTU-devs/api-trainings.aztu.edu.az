package com.eduplatform.eduplatform_backend.media;

import com.eduplatform.eduplatform_backend.support.AbstractIntegrationTest;
import com.eduplatform.eduplatform_backend.support.ApiClient;
import com.eduplatform.eduplatform_backend.support.Json;
import com.eduplatform.eduplatform_backend.support.TestFiles;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Who may read which file, and what deleting one through the video library may touch. */
class MediaAccessTest extends AbstractIntegrationTest {

    /** Room photos are uploaded by an admin and were unreadable to the tutors choosing a room. */
    @Test
    void aTutorSeesRoomPhotosButAParticipantDoesNot() {
        String adminToken = newAdminToken();
        UUID a = uploadMedia(adminToken, "a.png", "image/png", TestFiles.png());
        UUID b = uploadMedia(adminToken, "b.png", "image/png", TestFiles.png());
        api.post("/api/admin/rooms").bearer(adminToken)
                .json(Json.object("name", "Photo room", "roomNumber", word(), "capacity", 10,
                        "hourlyRate", 1, "currency", "AZN", "imageMediaIds", List.of(a, b)))
                .send().expectStatus(201);

        api.get("/api/media/" + a + "/content").bearer(login(newApprovedTutor("tutor").email())).send()
                .expectStatus(200);
        api.get("/api/media/" + a + "/content").bearer(login(newUser("learner", "USER").email())).send()
                .expectStatus(403);
    }

    /** The cover an admin set on a tutor's draft showed the tutor an empty upload box. */
    @Test
    void aTutorSeesTheCoverAnAdminPutOnTheirDraft() {
        TestTutor tutor = newApprovedTutor("tutor");
        String tutorToken = login(tutor.email());
        var course = createCourse(tutorToken, courseRequest("admin-cover", true));
        String adminToken = newAdminToken();
        UUID cover = uploadMedia(adminToken, "cover.png", "image/png", TestFiles.png());
        api.patch("/api/admin/courses/" + course.id()).bearer(adminToken)
                .json(Json.object("thumbnailMediaId", cover)).send().expectStatus(200);

        api.get("/api/media/" + cover + "/content").bearer(tutorToken).send().expectStatus(200);
        api.get("/api/media/" + cover + "/content").bearer(login(newApprovedTutor("other").email())).send()
                .expectStatus(403);
    }

    /**
     * The video library deleted any file its caller owned — a lesson's PDF, a portrait — and left
     * every reference pointing at bytes that were gone.
     */
    @Test
    void theVideoLibraryDeletesVideosOnlyAndDetachesThem() {
        String tutorToken = login(newApprovedTutor("tutor").email());
        UUID pdf = uploadMedia(tutorToken, "notes.pdf", "application/pdf", TestFiles.pdf());
        api.delete("/api/videos/" + pdf).bearer(tutorToken).send().expectError(404, "VIDEO_NOT_FOUND");
        api.get("/api/media/" + pdf + "/content").bearer(tutorToken).send().expectStatus(200);

        UUID mp4 = uploadMedia(tutorToken, "trailer.mp4", "video/mp4", TestFiles.mp4());
        Map<String, Object> request = courseRequest("trailered", true);
        request.put("trailerMediaId", mp4);
        var course = createCourse(tutorToken, request);

        api.delete("/api/videos/" + mp4).bearer(tutorToken).send().expectStatus(204);

        assertThat(jdbc.queryForObject("select trailer_media_id is null from courses where id = ?", Boolean.class,
                course.id())).as("the course lets go of the deleted trailer").isTrue();
    }

    /**
     * The upload handshake still works end to end now that the bytes are streamed outside a
     * transaction (a transaction around them held a pooled connection for the whole upload).
     */
    @Test
    void aVideoUploadStoresTheBytesAndCompletes() {
        String tutorToken = login(newApprovedTutor("tutor").email());
        UUID id = UUID.fromString(api.post("/api/videos/init").bearer(tutorToken)
                .json(Json.object("filename", "intro.mp4", "mime", "video/mp4", "sizeBytes", TestFiles.mp4().length))
                .send().expectStatus(200).data().path("videoId").asText());

        api.put("/api/videos/" + id + "/content").bearer(tutorToken)
                .body("video/mp4", new String(TestFiles.mp4(), java.nio.charset.StandardCharsets.ISO_8859_1))
                .send().expectStatus(204);
        api.post("/api/videos/" + id + "/complete").bearer(tutorToken).json(Json.object("title", "Intro"))
                .send().expectStatus(201);

        assertThat(jdbc.queryForObject("select status from media_files where id = ?", String.class, id))
                .isEqualTo("READY");
    }

    /** Admins author courses too, and had no way to put a video on one. */
    @Test
    void anAdminCanUseTheVideoLibrary() {
        ApiClient.Response list = api.get("/api/videos").bearer(newAdminToken()).send();
        list.expectStatus(200);
    }
}
