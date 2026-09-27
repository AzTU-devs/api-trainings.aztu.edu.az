package com.eduplatform.eduplatform_backend.course;

import com.eduplatform.eduplatform_backend.support.AbstractIntegrationTest;
import com.eduplatform.eduplatform_backend.support.ApiClient;
import com.eduplatform.eduplatform_backend.support.Json;
import com.eduplatform.eduplatform_backend.support.TestFiles;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Building a course: its module and lesson tree, who may read and change it, and the course form's rules. */
class CourseContentRulesTest extends AbstractIntegrationTest {

    /**
     * Positions are assigned by the server. The dashboard sent the number of live modules, which
     * after any deletion collided with a live or deleted module's slot, so a course could never
     * grow again once anything was deleted from it.
     */
    @Test
    void modulesAndLessonsCanBeAddedAfterOthersWereDeleted() {
        String token = login(newApprovedTutor("tutor").email());
        CourseRef course = createCourse(token, courseRequest("grow", true));
        UUID m0 = id(addModule(token, course.id(), 0).expectStatus(201));
        addModule(token, course.id(), 1).expectStatus(201);
        UUID m2 = id(addModule(token, course.id(), 2).expectStatus(201));

        api.delete("/api/portal/modules/" + m0).bearer(token).send().expectStatus(204);
        addModule(token, course.id(), 2).expectStatus(201);
        // Deleting the last one and adding again used to collide too.
        api.delete("/api/portal/modules/" + m2).bearer(token).send().expectStatus(204);
        addModule(token, course.id(), 2).expectStatus(201);

        UUID module = lastModule(token, course.id());
        UUID l0 = id(addLesson(token, module, 0).expectStatus(201));
        addLesson(token, module, 1).expectStatus(201);
        api.delete("/api/portal/lessons/" + l0).bearer(token).send().expectStatus(204);
        addLesson(token, module, 1).expectStatus(201);

        List<Integer> positions = new java.util.ArrayList<>();
        api.get("/api/portal/courses/" + course.id() + "/modules").bearer(token).send()
                .expectStatus(200).data().forEach(m -> positions.add(m.path("orderIndex").asInt()));
        assertThat(positions).hasSize(3).doesNotHaveDuplicates();
    }

    /** Any tutor could read any other tutor's draft modules and lessons, links and file ids included. */
    @Test
    void onlyTheCoursesTutorsAndStaffReadItsContent() {
        TestTutor owner = newApprovedTutor("owner");
        TestTutor coTutor = newApprovedTutor("co-tutor");
        String ownerToken = login(owner.email());
        CourseRef course = createCourse(ownerToken, courseRequest("private-draft", true));
        UUID module = id(addModule(ownerToken, course.id(), 0).expectStatus(201));
        api.put("/api/admin/courses/" + course.id() + "/tutors").bearer(newAdminToken())
                .json(Json.object("tutorIds", List.of(owner.profileId(), coTutor.profileId()),
                        "authorizedTutorId", owner.profileId()))
                .send().expectStatus(200);

        String intruder = login(newApprovedTutor("intruder").email());
        api.get("/api/portal/courses/" + course.id() + "/modules").bearer(intruder).send()
                .expectError(404, "COURSE_NOT_FOUND");
        api.get("/api/portal/modules/" + module + "/lessons").bearer(intruder).send()
                .expectError(404, "COURSE_NOT_FOUND");

        api.get("/api/portal/courses/" + course.id() + "/modules").bearer(ownerToken).send().expectStatus(200);
        api.get("/api/portal/courses/" + course.id() + "/modules").bearer(login(coTutor.email())).send()
                .expectStatus(200);
        api.get("/api/portal/courses/" + course.id() + "/modules").bearer(newAdminToken()).send().expectStatus(200);
        api.get("/api/portal/courses/" + course.id() + "/modules")
                .bearer(login(newUser("super", "SUPER_ADMIN").email())).send().expectStatus(200);
    }

    /** Admins author courses for the university, so they build the curriculum and archive it too. */
    @Test
    void anAdminBuildsTheCurriculumAndArchivesButAnotherTutorCannot() {
        TestTutor owner = newApprovedTutor("owner");
        CourseRef course = createCourse(login(owner.email()), courseRequest("admin-built", true));
        String adminToken = newAdminToken();

        UUID module = id(addModule(adminToken, course.id(), 0).expectStatus(201));
        addLesson(adminToken, module, 0).expectStatus(201);
        addModule(login(newApprovedTutor("other").email()), course.id(), 0).expectError(403, "NOT_COURSE_OWNER");

        JsonNode archived = api.post("/api/admin/courses/" + course.id() + "/archive").bearer(adminToken).send()
                .expectStatus(200).data();
        assertThat(archived.path("status").asText()).isEqualTo("ARCHIVED");
        assertThat(jdbc.queryForObject("""
                select count(*) from audit_logs where entity_type = 'COURSE' and entity_id = ? and action = 'ARCHIVE'
                """, Integer.class, course.id())).isEqualTo(1);
    }

    /** An admin could publish an online course with no lessons, and participants could enrol in nothing. */
    @Test
    void anOnlineCourseWithoutLessonsCanBeNeitherSubmittedNorPublished() {
        String tutorToken = login(newApprovedTutor("tutor").email());
        CourseRef course = createCourse(tutorToken, courseRequest("empty", true));

        api.post("/api/portal/courses/" + course.id() + "/submit").bearer(tutorToken).send()
                .expectError(422, "COURSE_HAS_NO_CONTENT");
        api.post("/api/admin/courses/" + course.id() + "/publish").bearer(newAdminToken()).send()
                .expectError(422, "COURSE_HAS_NO_CONTENT");
    }

    /** "Remove" followed by "Saved" used to change nothing: a null id meant "keep". */
    @Test
    void theCoverAndTrailerCanBeRemoved() {
        String token = login(newApprovedTutor("tutor").email());
        UUID png = uploadMedia(token, "cover.png", "image/png", TestFiles.png());
        UUID mp4 = uploadMedia(token, "trailer.mp4", "video/mp4", TestFiles.mp4());
        Map<String, Object> request = courseRequest("covered", true);
        request.put("thumbnailMediaId", png);
        request.put("trailerMediaId", mp4);
        CourseRef course = createCourse(token, request);

        JsonNode updated = api.patch("/api/portal/courses/" + course.id()).bearer(token)
                .json(Json.object("clearThumbnail", true, "clearTrailer", true)).send().expectStatus(200).data();

        assertThat(updated.path("thumbnailMediaId").isNull()).isTrue();
        assertThat(updated.path("trailerMediaId").isNull()).isTrue();
        assertThat(jdbc.queryForObject(
                "select thumbnail_media_id is null and trailer_media_id is null from courses where id = ?",
                Boolean.class, course.id())).isTrue();
    }

    @Test
    void badCourseInputIsA400ThatSaysWhatIsWrong() {
        String token = login(newApprovedTutor("tutor").email());

        Map<String, Object> noDates = offlineRequest(null, null, 20);
        api.post("/api/portal/courses").bearer(token).json(noDates).send().expectError(400, "OFFLINE_DATES_REQUIRED");

        Map<String, Object> freeWithPrice = courseRequest("free-priced", true);
        freeWithPrice.put("price", 25);
        api.post("/api/portal/courses").bearer(token).json(freeWithPrice).send().expectError(400, "INVALID_PRICE");
    }

    /** A PATCH naming only the city used to null both dates and zero the student limit. */
    @Test
    void aPartialOfflineUpdateKeepsWhatItDoesNotMention() {
        String token = login(newApprovedTutor("tutor").email());
        LocalDate start = LocalDate.now().plusDays(30);
        CourseRef course = createCourse(token, offlineRequest(start, start.plusDays(5), 20));

        JsonNode updated = api.patch("/api/portal/courses/" + course.id()).bearer(token)
                .json(Json.object("offlineDetails", Json.object("city", "Ganja"))).send().expectStatus(200).data();

        assertThat(updated.path("offlineDetails").path("city").asText()).isEqualTo("Ganja");
        assertThat(updated.path("offlineDetails").path("startDate").asText()).isEqualTo(start.toString());
        assertThat(updated.path("offlineDetails").path("studentLimit").asInt()).isEqualTo(20);

        // Turning a course free without naming a price makes it cost nothing, instead of failing.
        Map<String, Object> paid = courseRequest("paid-to-free", false);
        CourseRef paidCourse = createCourse(token, paid);
        JsonNode free = api.patch("/api/portal/courses/" + paidCourse.id()).bearer(token)
                .json(Json.object("free", true)).send().expectStatus(200).data();
        assertThat(free.path("price").decimalValue()).isZero();
    }

    /** Two people with the same course form open used to overwrite each other silently. */
    @Test
    void anEditBasedOnAStaleCopyIsRefused() {
        String token = login(newApprovedTutor("tutor").email());
        CourseRef course = createCourse(token, courseRequest("contested", true));
        long version = api.patch("/api/portal/courses/" + course.id()).bearer(token)
                .json(Json.object("subtitle", "First")).send().expectStatus(200).data().path("version").asLong();

        api.patch("/api/admin/courses/" + course.id()).bearer(newAdminToken())
                .json(Json.object("subtitle", "Admin's", "version", version)).send().expectStatus(200);
        api.patch("/api/portal/courses/" + course.id()).bearer(token)
                .json(Json.object("subtitle", "Tutor's", "version", version)).send()
                .expectError(409, "STALE_RESOURCE");
    }

    // ── helpers ─────────────────────────────────────────────────────────

    private ApiClient.Response addModule(String token, UUID courseId, int orderIndex) {
        return api.post("/api/portal/courses/" + courseId + "/modules").bearer(token)
                .json(Json.object("title", "Module " + word(), "orderIndex", orderIndex)).send();
    }

    private ApiClient.Response addLesson(String token, UUID moduleId, int orderIndex) {
        return api.post("/api/portal/modules/" + moduleId + "/lessons").bearer(token)
                .json(Json.object("title", "Lesson " + word(), "contentType", "TEXT", "durationSeconds", 60,
                        "orderIndex", orderIndex, "preview", false))
                .send();
    }

    private static UUID id(ApiClient.Response created) {
        return UUID.fromString(created.data().path("id").asText());
    }

    private UUID lastModule(String token, UUID courseId) {
        JsonNode modules = api.get("/api/portal/courses/" + courseId + "/modules").bearer(token).send()
                .expectStatus(200).data();
        return UUID.fromString(modules.get(modules.size() - 1).path("id").asText());
    }

    private static Map<String, Object> offlineRequest(LocalDate start, LocalDate end, int limit) {
        Map<String, Object> request = courseRequest("offline", true);
        request.put("courseType", "OFFLINE");
        request.remove("onlineDetails");
        request.put("offlineDetails", Json.object("startDate", start == null ? null : start.toString(),
                "endDate", end == null ? null : end.toString(), "studentLimit", limit, "city", "Baku"));
        return request;
    }
}
