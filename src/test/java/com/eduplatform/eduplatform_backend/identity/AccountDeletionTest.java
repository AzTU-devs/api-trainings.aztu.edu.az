package com.eduplatform.eduplatform_backend.identity;

import com.eduplatform.eduplatform_backend.support.AbstractIntegrationTest;
import com.eduplatform.eduplatform_backend.support.Json;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What a participant's account held goes with it. Deleting one used to cancel nothing: an
 * in-person seat stayed taken for good while the roster (which lists live accounts) showed nobody,
 * and the account's reviews went on counting in the course rating, where moderation could not even
 * hide them — setVisible loaded the deleted author and failed with 404.
 */
class AccountDeletionTest extends AbstractIntegrationTest {

    private static final String REPAIR = "V21__deleted_accounts_and_input_rules.sql";

    @Test
    void deletingAParticipantFreesTheirSeatAndRecountsTheCourse() {
        CourseRef course = oneSeatCourse();
        TestUser first = newUser("seat-holder", "USER");
        enrol(first, course.id());
        String superToken = login(newUser("super", "SUPER_ADMIN").email());

        api.delete("/api/admin/users/" + first.id()).bearer(superToken).send().expectStatus(204);

        JsonNode detail = api.get("/api/admin/courses/" + course.id()).bearer(superToken).send()
                .expectStatus(200).data();
        assertThat(detail.path("enrolledCount").asInt()).isZero();
        assertThat(detail.path("offlineDetails").path("enrolledCount").asInt()).isZero();
        assertThat(jdbc.queryForObject("select status from enrollments where user_id = ?", String.class, first.id()))
                .isEqualTo("CANCELLED");
        api.post("/api/portal/enrollments/courses/" + course.id() + "/free")
                .bearer(login(newUser("next", "USER").email())).send().expectStatus(201);
    }

    @Test
    void aDeletedParticipantsReviewStopsCountingInTheRating() {
        CourseRef course = publishedCourse(true);
        TestUser harsh = newUser("harsh", "USER");
        TestUser kind = newUser("kind", "USER");
        review(harsh, course.id(), 1);
        review(kind, course.id(), 5);
        assertThat(rating(course.id())).containsEntry("count", 2).containsEntry("avg", new BigDecimal("3.00"));

        api.delete("/api/admin/users/" + harsh.id()).bearer(login(newUser("super", "SUPER_ADMIN").email()))
                .send().expectStatus(204);

        assertThat(rating(course.id())).containsEntry("count", 1).containsEntry("avg", new BigDecimal("5.00"));
        assertThat(jdbc.queryForObject("select deleted_at is not null from course_reviews where user_id = ?",
                Boolean.class, harsh.id())).as("retired with the account").isTrue();
        JsonNode reviews = api.get("/api/public/courses/" + course.id() + "/reviews").send().expectStatus(200).data();
        assertThat(reviews.path("totalElements").asInt()).isEqualTo(1);
    }

    /** A review whose author was deleted the old way, before deleting retired reviews. */
    @Test
    void moderationActsOnAReviewWhoseAuthorWasDeletedTheOldWay() {
        CourseRef course = publishedCourse(true);
        TestUser gone = newUser("gone", "USER");
        UUID reviewId = review(gone, course.id(), 1);
        review(newUser("stays", "USER"), course.id(), 5);
        deleteAccountTheOldWay(gone.id());
        String adminToken = newAdminToken();

        api.get("/api/admin/reviews").bearer(adminToken).query("courseId", course.id()).send().expectStatus(200);
        JsonNode hidden = api.patch("/api/admin/reviews/" + reviewId + "/visibility").bearer(adminToken)
                .json(Json.object("visible", false)).send().expectStatus(200).data();

        assertThat(hidden.path("visible").asBoolean()).isFalse();
        assertThat(hidden.path("authorName").isNull()).as("a deleted account has no name to show").isTrue();
        assertThat(rating(course.id())).as("recomputed without the deleted author's review")
                .containsEntry("count", 1).containsEntry("avg", new BigDecimal("5.00"));
    }

    /**
     * Production holds participants deleted before any of this: their seats still taken, their
     * reviews still counted. V21 cancels, retires and recounts.
     */
    @Test
    void theRepairFreesTheSeatsAndRatingsOfAccountsDeletedTheOldWay() {
        CourseRef offline = oneSeatCourse();
        TestUser seated = newUser("old-seat", "USER");
        enrol(seated, offline.id());
        CourseRef online = publishedCourse(true);
        TestUser reviewer = newUser("old-review", "USER");
        review(reviewer, online.id(), 1);
        review(newUser("stays", "USER"), online.id(), 5);
        deleteAccountTheOldWay(seated.id());
        deleteAccountTheOldWay(reviewer.id());
        api.post("/api/portal/enrollments/courses/" + offline.id() + "/free")
                .bearer(login(newUser("blocked", "USER").email())).send().expectError(409, "COURSE_FULL");

        rerunMigration(REPAIR);
        rerunMigration(REPAIR);

        assertThat(jdbc.queryForObject("select status from enrollments where user_id = ?", String.class, seated.id()))
                .isEqualTo("CANCELLED");
        assertThat(jdbc.queryForObject("select enrolled_count from offline_course_details where course_id = ?",
                Integer.class, offline.id())).isZero();
        assertThat(rating(online.id())).containsEntry("count", 1).containsEntry("avg", new BigDecimal("5.00"));
        assertThat(jdbc.queryForObject(
                "select count(*) from refresh_tokens where user_id = ? and revoked_at is null",
                Integer.class, seated.id())).as("the deleted account's sessions are revoked").isZero();
        api.post("/api/portal/enrollments/courses/" + offline.id() + "/free")
                .bearer(login(newUser("next", "USER").email())).send().expectStatus(201);
    }

    private CourseRef oneSeatCourse() {
        String tutorToken = login(newApprovedTutor("tutor").email());
        Map<String, Object> request = courseRequest("one-seat", true);
        request.put("courseType", "OFFLINE");
        request.remove("onlineDetails");
        LocalDate start = LocalDate.now().plusDays(20);
        request.put("offlineDetails", Json.object("startDate", start.toString(),
                "endDate", start.plusDays(2).toString(), "studentLimit", 1, "city", "Baku"));
        CourseRef course = createCourse(tutorToken, request);
        publish(tutorToken, course.id());
        return course;
    }

    private void enrol(TestUser user, UUID courseId) {
        api.post("/api/portal/enrollments/courses/" + courseId + "/free").bearer(login(user.email()))
                .send().expectStatus(201);
    }

    private UUID review(TestUser user, UUID courseId, int stars) {
        String token = login(user.email());
        api.post("/api/portal/enrollments/courses/" + courseId + "/free").bearer(token).send().expectStatus(201);
        return UUID.fromString(api.post("/api/portal/courses/" + courseId + "/reviews").bearer(token)
                .json(Json.object("rating", stars, "title", "Review", "body", "Integration test review"))
                .send().expectStatus(201).data().path("id").asText());
    }

    private Map<String, Object> rating(UUID courseId) {
        return jdbc.queryForMap("select rating_count as count, rating_avg as avg from courses where id = ?", courseId);
    }
}
