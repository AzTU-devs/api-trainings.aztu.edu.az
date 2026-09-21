package com.eduplatform.eduplatform_backend.course;

import com.eduplatform.eduplatform_backend.support.AbstractIntegrationTest;
import com.eduplatform.eduplatform_backend.support.ApiClient;
import com.eduplatform.eduplatform_backend.support.Json;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.eduplatform.eduplatform_backend.support.Json.field;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Course authoring by an admin: creating on behalf of the university, editing any course and
 * publishing outright, without the tutor submit-then-decide round trip.
 *
 * <p>An ADMIN runs the platform day to day, so every one of these has to work for that role and
 * not only for a super admin — V12 grants ADMIN the {@code course:create_any} that V10 withheld.
 * The ownership rule these endpoints relax is enforced one layer down, so the tutor portal is
 * checked here too: relaxing it for an admin must not relax it for a tutor.
 */
class AdminCourseAuthoringTest extends AbstractIntegrationTest {

    @Test
    void anAdminCreatesACourseForANominatedTutor() {
        TestTutor tutor = newApprovedTutor("tutor");

        JsonNode course = createAsAdmin(newAdminToken(), tutor, courseRequest("admin-made", true))
                .expectStatus(201).data();

        assertThat(course.path("status").asText()).as("a new course is never live on creation").isEqualTo("DRAFT");
        assertThat(course.path("tutorId").asText()).isEqualTo(tutor.profileId().toString());
        assertThat(rosterOf(course)).containsExactly(tutor.profileId().toString());
    }

    /**
     * The whole of V12 in one assertion. {@code course:create_any} was granted to SUPER_ADMIN
     * alone, so this endpoint answered 403 to the role that actually runs the platform.
     */
    @Test
    void createIsReachableByAnAdminAndNotOnlyBySuperAdmin() {
        TestTutor tutor = newApprovedTutor("tutor");

        createAsAdmin(newAdminToken(), tutor, courseRequest("by-admin", true)).expectStatus(201);
        createAsAdmin(login(newUser("superadmin", "SUPER_ADMIN").email()), tutor,
                courseRequest("by-superadmin", true)).expectStatus(201);
    }

    @Test
    void anAdminEditsACourseTheyDoNotTeach() {
        TestTutor tutor = newApprovedTutor("tutor");
        CourseRef course = createCourse(login(tutor.email()), courseRequest("owned", true));
        String newTitle = "Retitled by an admin " + word();

        JsonNode edited = api.patch("/api/admin/courses/" + course.id()).bearer(newAdminToken())
                .json(Json.object("title", newTitle, "subtitle", "Edited centrally"))
                .send().expectStatus(200).data();

        assertThat(edited.path("title").asText()).isEqualTo(newTitle);
        assertThat(titleInDb(course.id())).isEqualTo(newTitle);
        // Partial update: an omitted field keeps its value rather than being nulled.
        assertThat(edited.path("language").asText()).isEqualTo("en");
    }

    /** An admin maintains the catalogue, so a live course is editable in place. */
    @Test
    void anAdminEditsAPublishedCourseWithoutTakingItDown() {
        TestTutor tutor = newApprovedTutor("tutor");
        String tutorToken = login(tutor.email());
        CourseRef course = createCourse(tutorToken, courseRequest("live", true));
        publish(tutorToken, course.id());

        JsonNode edited = api.patch("/api/admin/courses/" + course.id()).bearer(newAdminToken())
                .json(Json.object("subtitle", "Corrected while live"))
                .send().expectStatus(200).data();

        assertThat(edited.path("status").asText()).isEqualTo("PUBLISHED");
        assertThat(edited.path("subtitle").asText()).isEqualTo("Corrected while live");
    }

    /**
     * The admin edit drops the ownership check; the tutor's own edit must not. Both land on the
     * same service method, which is exactly why this is worth asserting.
     */
    @Test
    void aTutorStillCannotEditAnotherTutorsCourse() {
        TestTutor owner = newApprovedTutor("owner");
        CourseRef course = createCourse(login(owner.email()), courseRequest("owned", true));
        String intruder = login(newApprovedTutor("intruder").email());

        api.patch("/api/portal/courses/" + course.id()).bearer(intruder)
                .json(Json.object("title", "Taken over"))
                .send().expectError(403, "NOT_COURSE_OWNER");

        assertThat(titleInDb(course.id())).isEqualTo(course.title());
    }

    @Test
    void publishingADraftPutsItInTheCatalogueWithoutAReviewRoundTrip() {
        TestTutor tutor = newApprovedTutor("tutor");
        String adminToken = newAdminToken();
        UUID courseId = adminCourse(adminToken, tutor, courseRequest("publish-me", true));

        JsonNode published = publishAsAdmin(adminToken, courseId).expectStatus(200).data();

        assertThat(published.path("status").asText()).isEqualTo("PUBLISHED");
        assertThat(published.path("publishedAt").isNull()).as("publishedAt is stamped").isFalse();
        assertThat(statusInDb(courseId)).isEqualTo("PUBLISHED");
        // The point of the whole feature: what an admin publishes is what the public site serves.
        assertThat(field(api.get("/api/public/courses/search")
                .query("q", published.path("title").asText()).send().expectStatus(200).data(), "id"))
                .contains(courseId.toString());
    }

    /**
     * The catalogue orders by published_at, so re-publishing a course that was taken down for a
     * correction must not float an old training to the top as though it were new.
     */
    @Test
    void republishingKeepsTheFirstPublicationDate() {
        TestTutor tutor = newApprovedTutor("tutor");
        String adminToken = newAdminToken();
        UUID courseId = adminCourse(adminToken, tutor, courseRequest("republished", true));

        String firstPublication = publishAsAdmin(adminToken, courseId)
                .expectStatus(200).data().path("publishedAt").asText();
        unpublishAsAdmin(adminToken, courseId).expectStatus(200);
        String secondPublication = publishAsAdmin(adminToken, courseId)
                .expectStatus(200).data().path("publishedAt").asText();

        assertThat(secondPublication).isEqualTo(firstPublication);
        assertThat(publishedAtInDb(courseId)).isNotNull();
    }

    /** Publishing a course that is already live is a no-op, so the dashboard button is safe twice. */
    @Test
    void publishingTwiceIsIdempotent() {
        TestTutor tutor = newApprovedTutor("tutor");
        String adminToken = newAdminToken();
        UUID courseId = adminCourse(adminToken, tutor, courseRequest("twice", true));

        String first = publishAsAdmin(adminToken, courseId).expectStatus(200).data().path("publishedAt").asText();
        JsonNode again = publishAsAdmin(adminToken, courseId).expectStatus(200).data();

        assertThat(again.path("status").asText()).isEqualTo("PUBLISHED");
        assertThat(again.path("publishedAt").asText()).isEqualTo(first);
    }

    @Test
    void unpublishingReturnsACourseToDraftAndKeepsItsPublicationDate() {
        TestTutor tutor = newApprovedTutor("tutor");
        String adminToken = newAdminToken();
        UUID courseId = adminCourse(adminToken, tutor, courseRequest("taken-down", true));
        publishAsAdmin(adminToken, courseId).expectStatus(200);
        Instant publishedAt = publishedAtInDb(courseId);

        JsonNode unpublished = unpublishAsAdmin(adminToken, courseId).expectStatus(200).data();

        assertThat(unpublished.path("status").asText()).isEqualTo("DRAFT");
        assertThat(statusInDb(courseId)).isEqualTo("DRAFT");
        // The date the course first went live, not a flag for "is live now" — status is that.
        assertThat(publishedAtInDb(courseId)).isEqualTo(publishedAt);
    }

    /** A course out of the catalogue is off the public site, whatever its publication date says. */
    @Test
    void anUnpublishedCourseLeavesThePublicCatalogue() {
        TestTutor tutor = newApprovedTutor("tutor");
        String adminToken = newAdminToken();
        UUID courseId = adminCourse(adminToken, tutor, courseRequest("withdrawn", true));
        String title = publishAsAdmin(adminToken, courseId).expectStatus(200).data().path("title").asText();

        unpublishAsAdmin(adminToken, courseId).expectStatus(200);

        assertThat(field(api.get("/api/public/courses/search").query("q", title)
                .send().expectStatus(200).data(), "id")).doesNotContain(courseId.toString());
    }

    /**
     * {@code AuditService.record} swallows its own failures so a broken audit write can never fail
     * a mutation — which also means only a test that reads the table notices one.
     */
    @Test
    void publishingIsAuditedAgainstTheAdminWhoDidIt() {
        TestTutor tutor = newApprovedTutor("tutor");
        TestUser admin = newUser("admin", "ADMIN");
        String adminToken = login(admin.email());
        UUID courseId = adminCourse(adminToken, tutor, courseRequest("audited", true));

        publishAsAdmin(adminToken, courseId).expectStatus(200);

        assertThat(jdbc.queryForObject("""
                select count(*) from audit_logs
                where entity_type = 'COURSE' and entity_id = ? and action = 'PUBLISH' and actor_id = ?
                """, Integer.class, courseId, admin.id())).isEqualTo(1);
    }

    @Test
    void editingSomebodyElsesCourseIsAudited() {
        TestTutor tutor = newApprovedTutor("tutor");
        CourseRef course = createCourse(login(tutor.email()), courseRequest("audited-edit", true));
        TestUser admin = newUser("admin", "ADMIN");

        api.patch("/api/admin/courses/" + course.id()).bearer(login(admin.email()))
                .json(Json.object("title", "Retitled " + word())).send().expectStatus(200);

        assertThat(jdbc.queryForObject("""
                select count(*) from audit_logs
                where entity_type = 'COURSE' and entity_id = ? and action = 'UPDATE' and actor_id = ?
                """, Integer.class, course.id(), admin.id())).isEqualTo(1);
    }

    /**
     * A tutor holds none of {@code course:create_any}, {@code course:manage} or
     * {@code course:publish}, so every admin authoring endpoint has to refuse them.
     */
    @Test
    void aTutorIsRefusedByEveryAdminAuthoringEndpoint() {
        TestTutor tutor = newApprovedTutor("tutor");
        String tutorToken = login(tutor.email());
        CourseRef own = createCourse(tutorToken, courseRequest("own", true));

        createAsAdmin(tutorToken, tutor, courseRequest("forbidden", true)).expectStatus(403);
        api.patch("/api/admin/courses/" + own.id()).bearer(tutorToken)
                .json(Json.object("title", "Nope")).send().expectStatus(403);
        publishAsAdmin(tutorToken, own.id()).expectStatus(403);
        unpublishAsAdmin(tutorToken, own.id()).expectStatus(403);

        assertThat(statusInDb(own.id())).as("the tutor's own course is untouched").isEqualTo("DRAFT");
    }

    /** A plain learner holds no course permission at all. */
    @Test
    void anOrdinaryUserIsRefusedByEveryAdminAuthoringEndpoint() {
        CourseRef course = publishedCourse(true);
        String userToken = login(newUser("student", "USER").email());

        api.patch("/api/admin/courses/" + course.id()).bearer(userToken)
                .json(Json.object("title", "Nope")).send().expectStatus(403);
        publishAsAdmin(userToken, course.id()).expectStatus(403);
        unpublishAsAdmin(userToken, course.id()).expectStatus(403);
    }

    @Test
    void editingACourseThatDoesNotExistIsNotFound() {
        api.patch("/api/admin/courses/" + UUID.randomUUID()).bearer(newAdminToken())
                .json(Json.object("title", "Ghost")).send().expectError(404, "COURSE_NOT_FOUND");
        publishAsAdmin(newAdminToken(), UUID.randomUUID()).expectError(404, "COURSE_NOT_FOUND");
    }

    // ── helpers ─────────────────────────────────────────────────────────

    private ApiClient.Response createAsAdmin(String token, TestTutor tutor, Map<String, Object> course) {
        return api.post("/api/admin/courses").bearer(token)
                .json(Json.object(
                        "course", course,
                        "tutorIds", List.of(tutor.profileId()),
                        "authorizedTutorId", tutor.profileId()))
                .send();
    }

    private UUID adminCourse(String token, TestTutor tutor, Map<String, Object> course) {
        return UUID.fromString(createAsAdmin(token, tutor, course).expectStatus(201).data().path("id").asText());
    }

    private ApiClient.Response publishAsAdmin(String token, UUID courseId) {
        return api.post("/api/admin/courses/" + courseId + "/publish").bearer(token).send();
    }

    private ApiClient.Response unpublishAsAdmin(String token, UUID courseId) {
        return api.post("/api/admin/courses/" + courseId + "/unpublish").bearer(token).send();
    }

    /** The tutor ids on the course's teaching roster, in the order the API returned them. */
    private static List<String> rosterOf(JsonNode course) {
        return course.path("tutors").findValuesAsText("tutorId");
    }

    private String titleInDb(UUID courseId) {
        return jdbc.queryForObject("select title from courses where id = ?", String.class, courseId);
    }

    private String statusInDb(UUID courseId) {
        return jdbc.queryForObject("select status from courses where id = ?", String.class, courseId);
    }

    private Instant publishedAtInDb(UUID courseId) {
        return jdbc.queryForObject("select published_at from courses where id = ?", Instant.class, courseId);
    }
}
