package com.eduplatform.eduplatform_backend.course;

import com.eduplatform.eduplatform_backend.support.AbstractIntegrationTest;
import com.eduplatform.eduplatform_backend.support.ApiClient;
import com.eduplatform.eduplatform_backend.support.Json;
import com.eduplatform.eduplatform_backend.support.TestFiles;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * thumbnailMediaId and trailerMediaId on course create, admin create and PATCH. They used to be
 * accepted and silently dropped, so no course could get a cover through the API. The media must
 * exist (404 MEDIA_NOT_FOUND), belong to the caller unless the caller is an admin
 * (403 MEDIA_FORBIDDEN), be READY, and be an image for the thumbnail or a video for the trailer
 * (422 INVALID_MEDIA_FOR_FIELD). PATCH follows the request's partial-update rule: an absent or
 * null id leaves the current media in place.
 */
class CourseMediaTest extends AbstractIntegrationTest {

    @Test
    void aPngSetByPatchIsPersistedAndBecomesTheCatalogThumbnailOncePublished() {
        String token = login(newApprovedTutor("tutor").email());
        byte[] cover = TestFiles.png();
        UUID png = uploadMedia(token, "cover.png", "image/png", cover);
        CourseRef course = createCourse(token, courseRequest("covered", true));

        JsonNode patched = patch(token, course, Json.object("thumbnailMediaId", png)).expectStatus(200).data();
        assertThat(patched.path("thumbnailMediaId").asText()).isEqualTo(png.toString());
        assertThat(thumbnailInDb(course)).isEqualTo(png);

        publish(token, course.id());
        JsonNode catalog = api.get("/api/public/courses").query("q", course.title()).send().expectStatus(200).data();
        String thumbnailUrl = Json.find(catalog, "slug", course.slug()).path("thumbnailUrl").asText();
        assertThat(thumbnailUrl).as("thumbnailUrl in the catalogue entry, page: %s", catalog)
                .isEqualTo("/api/public/media/" + png + "/content");

        ApiClient.Response image = api.get(thumbnailUrl).send().expectStatus(200);
        assertThat(image.header("Content-Type")).hasValueSatisfying(type -> assertThat(type).startsWith("image/png"));
        assertThat(image.bytes()).isEqualTo(cover);
    }

    @Test
    void aThumbnailGivenAtCreateIsPersisted() {
        String token = login(newApprovedTutor("tutor").email());
        UUID png = uploadMedia(token, "cover.png", "image/png", TestFiles.png());
        Map<String, Object> request = courseRequest("created-covered", true);
        request.put("thumbnailMediaId", png);

        CourseRef course = createCourse(token, request);

        assertThat(thumbnailInDb(course)).isEqualTo(png);
    }

    /** Admins assemble courses from whatever the university uploaded, so ownership does not bind them. */
    @Test
    void aSuperAdminCreatingACourseMayUseMediaTheTutorUploaded() {
        TestTutor tutor = newApprovedTutor("tutor");
        UUID png = uploadMedia(login(tutor.email()), "cover.png", "image/png", TestFiles.png());
        Map<String, Object> course = courseRequest("admin-made", true);
        course.put("thumbnailMediaId", png);

        JsonNode created = api.post("/api/admin/courses").bearer(login(newUser("super", "SUPER_ADMIN").email()))
                .json(Json.object("course", course, "tutorIds", List.of(tutor.profileId()),
                        "authorizedTutorId", tutor.profileId()))
                .send().expectStatus(201).data();

        assertThat(created.path("thumbnailMediaId").asText()).isEqualTo(png.toString());
        assertThat(jdbc.queryForObject("select thumbnail_media_id from courses where id = ?", UUID.class,
                UUID.fromString(created.path("id").asText()))).isEqualTo(png);
    }

    @Test
    void anUploadedVideoCanBeTheTrailer() {
        String token = login(newApprovedTutor("tutor").email());
        UUID mp4 = uploadMedia(token, "trailer.mp4", "video/mp4", TestFiles.mp4());
        CourseRef course = createCourse(token, courseRequest("trailered", true));

        patch(token, course, Json.object("trailerMediaId", mp4)).expectStatus(200);

        assertThat(jdbc.queryForObject("select trailer_media_id from courses where id = ?", UUID.class, course.id()))
                .isEqualTo(mp4);
    }

    @Test
    void aPdfCannotBeTheThumbnail() {
        String token = login(newApprovedTutor("tutor").email());
        UUID pdf = uploadMedia(token, "syllabus.pdf", "application/pdf", TestFiles.pdf());
        CourseRef course = createCourse(token, courseRequest("pdf-cover", true));

        patch(token, course, Json.object("thumbnailMediaId", pdf)).expectError(422, "INVALID_MEDIA_FOR_FIELD");

        assertThat(thumbnailInDb(course)).isNull();
    }

    @Test
    void anImageCannotBeTheTrailer() {
        String token = login(newApprovedTutor("tutor").email());
        UUID png = uploadMedia(token, "still.png", "image/png", TestFiles.png());
        CourseRef course = createCourse(token, courseRequest("still-trailer", true));

        patch(token, course, Json.object("trailerMediaId", png)).expectError(422, "INVALID_MEDIA_FOR_FIELD");
    }

    /** A published course's thumbnail is served anonymously, so this would publish someone else's private file. */
    @Test
    void anotherUsersUploadCannotBeTheThumbnail() {
        UUID someoneElses = uploadMedia(login(newUser("student", "USER").email()), "private.png", "image/png",
                TestFiles.png());
        String token = login(newApprovedTutor("tutor").email());
        CourseRef course = createCourse(token, courseRequest("borrowed", true));

        patch(token, course, Json.object("thumbnailMediaId", someoneElses)).expectError(403, "MEDIA_FORBIDDEN");

        assertThat(thumbnailInDb(course)).isNull();
    }

    @Test
    void anUnknownMediaIdIsNotFound() {
        String token = login(newApprovedTutor("tutor").email());
        CourseRef course = createCourse(token, courseRequest("phantom", true));

        patch(token, course, Json.object("thumbnailMediaId", UUID.randomUUID())).expectError(404, "MEDIA_NOT_FOUND");
    }

    @Test
    void mediaThatHasNotFinishedUploadingIsRejected() {
        TestTutor tutor = newApprovedTutor("tutor");
        UUID pending = UUID.randomUUID();
        jdbc.update("""
                insert into media_files (id, owner_user_id, storage, object_key, mime_type, byte_size, status, visibility)
                values (?, ?, 'LOCAL', ?, 'image/png', 1, 'PENDING', 'PRIVATE')
                """, pending, tutor.userId(), "pending/" + pending + ".png");
        String token = login(tutor.email());
        CourseRef course = createCourse(token, courseRequest("pending-cover", true));

        patch(token, course, Json.object("thumbnailMediaId", pending)).expectError(422, "INVALID_MEDIA_FOR_FIELD");
    }

    /** Leaving a field out is how every partial update leaves it alone; an unrelated edit must not clear the cover. */
    @Test
    void aPatchWithoutAThumbnailKeepsTheCurrentOne() {
        String token = login(newApprovedTutor("tutor").email());
        UUID png = uploadMedia(token, "cover.png", "image/png", TestFiles.png());
        CourseRef course = createCourse(token, courseRequest("kept-cover", true));
        patch(token, course, Json.object("thumbnailMediaId", png)).expectStatus(200);

        patch(token, course, Json.object("title", "Renamed course")).expectStatus(200);

        assertThat(thumbnailInDb(course)).isEqualTo(png);
    }

    /**
     * The upload response used to carry createdAt null: it was built from the instance passed to
     * save(), not the managed copy save() returned, which is the one auditing stamps.
     */
    @Test
    void theUploadResponseSaysWhenTheFileWasStored() {
        JsonNode media = upload(login(newApprovedTutor("tutor").email()), "cover.png", "image/png", TestFiles.png())
                .expectStatus(201).data();

        assertThat(media.path("createdAt").isNull() || media.path("createdAt").isMissingNode())
                .as("createdAt missing from %s", media).isFalse();
        assertThat(media.path("status").asText()).isEqualTo("READY");
    }

    private ApiClient.Response patch(String token, CourseRef course, Map<String, Object> changes) {
        return api.patch("/api/portal/courses/" + course.id()).bearer(token).json(changes).send();
    }

    private UUID thumbnailInDb(CourseRef course) {
        return jdbc.queryForObject("select thumbnail_media_id from courses where id = ?", UUID.class, course.id());
    }
}
