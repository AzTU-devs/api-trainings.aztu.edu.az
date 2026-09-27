package com.eduplatform.eduplatform_backend.admin;

import com.eduplatform.eduplatform_backend.support.AbstractIntegrationTest;
import com.eduplatform.eduplatform_backend.support.Json;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Administration features that were missing or half-wired. */
class AdminToolsTest extends AbstractIntegrationTest {

    /** ADMIN lacked notification:read_own, so the bell answered 403 on every page. */
    @Test
    void anAdminReadsTheirOwnNotifications() {
        String adminToken = newAdminToken();

        api.get("/api/portal/notifications").bearer(adminToken).send().expectStatus(200);
        api.get("/api/portal/notifications/unread-count").bearer(adminToken).send().expectStatus(200);
        api.post("/api/portal/notifications/read-all").bearer(adminToken).send().expectStatus(200);
    }

    /** review:moderate had nothing to act on, so an abusive review could not be taken down. */
    @Test
    void aReviewCanBeHiddenAndTheRatingFollows() {
        CourseRef course = publishedCourse(true);
        String learner = login(newUser("reviewer", "USER").email());
        api.post("/api/portal/enrollments/courses/" + course.id() + "/free").bearer(learner).send().expectStatus(201);
        String reviewId = api.post("/api/portal/courses/" + course.id() + "/reviews").bearer(learner)
                .json(Json.object("rating", 1, "title", "Abusive", "body", "Something abusive"))
                .send().expectStatus(201).data().path("id").asText();
        String adminToken = newAdminToken();

        api.patch("/api/admin/reviews/" + reviewId + "/visibility").bearer(adminToken)
                .json(Json.object("visible", false)).send().expectStatus(200);

        JsonNode visible = api.get("/api/public/courses/" + course.id() + "/reviews").send().expectStatus(200).data();
        assertThat(Json.field(visible, "id")).doesNotContain(reviewId);
        assertThat(jdbc.queryForObject("select rating_count from courses where id = ?", Integer.class, course.id()))
                .isZero();
        JsonNode hidden = api.get("/api/admin/reviews").bearer(adminToken).query("courseId", course.id())
                .query("visible", false).send().expectStatus(200).data();
        assertThat(Json.field(hidden, "id")).contains(reviewId);
    }

    /** Search used to match only the action and the resource type. */
    @Test
    void theAuditLogIsSearchableByActorResourceAndAddress() {
        TestUser admin = newUser("audited-admin", "ADMIN");
        String adminToken = login(admin.email());
        TestUser target = newUser("audited-target", "USER");
        api.put("/api/admin/users/" + target.id()).bearer(adminToken).header("X-Forwarded-For", "198.51.100.77")
                .json(Json.object("fullName", "Renamed Target")).send().expectStatus(200);
        String superToken = login(newUser("auditor", "SUPER_ADMIN").email());

        assertThat(Json.field(search(superToken, admin.email()), "resourceId")).contains(target.id().toString());
        assertThat(Json.field(search(superToken, target.id().toString()), "resourceId"))
                .contains(target.id().toString());
        assertThat(Json.field(search(superToken, "198.51.100.77"), "resourceId")).contains(target.id().toString());
        // Signing in is recorded too, so the LOGIN filter has something to show.
        assertThat(jdbc.queryForObject("select count(*) from audit_logs where action = 'LOGIN' and actor_id = ?",
                Integer.class, admin.id())).isPositive();
    }

    /** A PATCH that changed nothing wrote an UPDATE row whose before and after were the same. */
    @Test
    void anEditThatChangesNothingIsNotAudited() {
        TestTutor tutor = newApprovedTutor("no-op");
        String token = login(tutor.email());

        api.patch("/api/portal/tutor/me").bearer(token).json(Json.object()).send().expectStatus(200);

        assertThat(jdbc.queryForObject(
                "select count(*) from audit_logs where entity_type = 'TUTOR_PROFILE' and entity_id = ?",
                Integer.class, tutor.profileId())).isZero();
    }

    /** A deleted account's address was held for ever by the users table's unique constraint. */
    @Test
    void aDeletedAccountsAddressCanRegisterAgain() {
        TestUser gone = newUser("recycled", "USER");
        api.delete("/api/admin/users/" + gone.id()).bearer(newAdminToken()).send().expectStatus(204);

        api.post("/api/auth/register")
                .json(Json.object("email", gone.email(), "password", PASSWORD, "firstName", "Second", "lastName", "Life"))
                .send().expectStatus(201);
        assertThat(jdbc.queryForObject("select count(*) from users where email = ? and deleted_at is null",
                Integer.class, gone.email())).isEqualTo(1);
    }

    private JsonNode search(String token, String text) {
        return api.get("/api/super/audit-logs").bearer(token).query("search", text).query("size", 100)
                .send().expectStatus(200).data();
    }
}
