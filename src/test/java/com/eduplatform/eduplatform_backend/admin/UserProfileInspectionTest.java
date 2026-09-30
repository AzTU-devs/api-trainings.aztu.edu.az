package com.eduplatform.eduplatform_backend.admin;

import com.eduplatform.eduplatform_backend.support.AbstractIntegrationTest;
import com.eduplatform.eduplatform_backend.support.ApiClient;
import com.eduplatform.eduplatform_backend.support.Json;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * GET /api/super/users/{userId}/profile: a super admin's inspection of one account. It is behind
 * {@code user:inspect}, which V22 grants to SUPER_ADMIN alone, it shows deleted accounts too, and it
 * must never carry a secret.
 */
class UserProfileInspectionTest extends AbstractIntegrationTest {

    @Test
    void anAdminIsRefused() {
        TestUser someone = newUser("inspected", "USER");

        profile(newAdminToken(), someone.id()).expectError(403, "FORBIDDEN");
    }

    @Test
    void anUnknownIdIsNotFound() {
        profile(superToken(), UUID.randomUUID()).expectError(404, "USER_NOT_FOUND");
    }

    @Test
    void aParticipantsProfileHasEveryPartAndNoSecret() {
        TestUser learner = newUser("learner", "USER");
        String learnerToken = login(learner.email());
        CourseRef online = publishedCourse(true);
        UUID inPerson = publishedOneTimeCourse();
        UUID onlineEnrollment = enroll(learnerToken, online.id());
        UUID inPersonEnrollment = enroll(learnerToken, inPerson);
        markAttendance(inPerson, inPersonEnrollment, "PRESENT", "ABSENT");
        api.post("/api/portal/courses/" + online.id() + "/reviews").bearer(learnerToken)
                .json(Json.object("rating", 5, "title", "Great", "body", "Learned a lot")).send().expectStatus(201);

        ApiClient.Response response = profile(superToken(), learner.id()).expectStatus(200);
        JsonNode data = response.data();

        assertThat(fieldNames(data)).containsExactly("account", "expert", "learner", "activity");
        JsonNode account = data.path("account");
        assertThat(fieldNames(account)).containsExactly("id", "email", "phone", "firstName", "lastName", "fullName",
                "finKod", "locale", "status", "roles", "emailVerifiedAt", "lastLoginAt", "failedLogins",
                "lockedUntil", "createdAt", "updatedAt", "deletedAt", "avatarUrl", "identities");
        assertThat(account.path("id").asText()).isEqualTo(learner.id().toString());
        assertThat(account.path("email").asText()).isEqualTo(learner.email());
        assertThat(account.path("fullName").asText()).isEqualTo("Test learner");
        assertThat(Json.texts(account.path("roles"))).containsExactly("USER");
        assertThat(account.path("deletedAt").isNull()).isTrue();
        assertThat(data.path("expert").isNull()).as("not an expert").isTrue();

        JsonNode learnerPart = data.path("learner");
        assertThat(fieldNames(learnerPart)).containsExactly("enrollments", "orders", "reviews", "stats");
        JsonNode newest = learnerPart.path("enrollments").get(0);
        assertThat(newest.path("id").asText()).as("newest first").isEqualTo(inPersonEnrollment.toString());
        assertThat(newest.path("courseType").asText()).isEqualTo("ONE_TIME");
        assertThat(newest.path("attendance").path("total").asLong()).isEqualTo(2);
        assertThat(newest.path("attendance").path("byStatus").path("PRESENT").asLong()).isEqualTo(1);
        assertThat(newest.path("attendance").path("byStatus").path("ABSENT").asLong()).isEqualTo(1);
        JsonNode onlineRow = learnerPart.path("enrollments").get(1);
        assertThat(onlineRow.path("id").asText()).isEqualTo(onlineEnrollment.toString());
        assertThat(onlineRow.has("attendance") && onlineRow.path("attendance").isNull())
                .as("no attendance on an online course: %s", onlineRow).isTrue();
        assertThat(onlineRow.path("lessonsTotal").asLong()).as("publishing gave it a lesson").isEqualTo(1);
        assertThat(onlineRow.path("lessonsCompleted").asLong()).isZero();
        assertThat(learnerPart.path("reviews").get(0).path("title").asText()).isEqualTo("Great");
        assertThat(learnerPart.path("stats").path("enrollmentCount").asInt()).isEqualTo(2);
        assertThat(learnerPart.path("stats").path("activeCount").asInt()).isEqualTo(2);

        JsonNode activity = data.path("activity");
        assertThat(fieldNames(activity)).containsExactly("sessions", "securityEvents", "auditTrail");
        assertThat(activity.path("sessions").get(0).path("active").asBoolean()).isTrue();
        assertThat(Json.texts(actions(activity.path("auditTrail")))).contains("LOGIN");

        assertThat(response.body()).doesNotContainIgnoringCase("password")
                .doesNotContainIgnoringCase("tokenHash").doesNotContainIgnoringCase("otp")
                .doesNotContainIgnoringCase("rawProfile");
    }

    @Test
    void anExpertsProfileListsWhatTheyTeach() {
        TestTutor tutor = newApprovedTutor("expert");
        String tutorToken = login(tutor.email());
        api.patch("/api/portal/tutor/me").bearer(tutorToken)
                .json(Json.object("customExpertise", List.of("Tribology"))).send().expectStatus(200);
        CourseRef course = createCourse(tutorToken, courseRequest("taught", true));

        JsonNode expert = profile(superToken(), tutor.userId()).expectStatus(200).data().path("expert");

        assertThat(expert.path("id").asText()).isEqualTo(tutor.profileId().toString());
        assertThat(expert.path("approvalStatus").asText()).isEqualTo("APPROVED");
        assertThat(Json.texts(expert.path("customExpertise"))).containsExactly("Tribology");
        assertThat(expert.path("courses").get(0).path("id").asText()).isEqualTo(course.id().toString());
        assertThat(expert.path("courses").get(0).path("editor").asBoolean()).isTrue();
        assertThat(expert.path("stats").path("courseCount").asInt()).isEqualTo(1);
        assertThat(expert.path("stats").path("publishedCourseCount").asInt()).isZero();
        for (String list : List.of("expertise", "approvalHistory", "roomBookings")) {
            assertThat(expert.path(list).isArray()).as(list).isTrue();
        }
    }

    /** The point is inspection, so a deleted account is shown, not answered as unknown. */
    @Test
    void aDeletedAccountIsStillShown() {
        TestUser gone = newUser("deleted", "USER");
        api.delete("/api/admin/users/" + gone.id()).bearer(newAdminToken()).send().expectStatus(204);

        JsonNode account = profile(superToken(), gone.id()).expectStatus(200).data().path("account");

        assertThat(account.path("id").asText()).isEqualTo(gone.id().toString());
        assertThat(account.path("deletedAt").isNull()).as("deletedAt in %s", account).isFalse();
    }

    // ── helpers ─────────────────────────────────────────────────────────

    private ApiClient.Response profile(String token, UUID userId) {
        return api.get("/api/super/users/" + userId + "/profile").bearer(token).send();
    }

    private String superToken() {
        return login(newUser("inspector", "SUPER_ADMIN").email());
    }

    /** A free ONE_TIME course, published the way the tutor portal does it. */
    private UUID publishedOneTimeCourse() {
        String token = login(newApprovedTutor("one-time").email());
        Map<String, Object> request = courseRequest("one-time", true);
        request.put("courseType", "ONE_TIME");
        request.remove("onlineDetails");
        request.put("offlineDetails", Json.object("startDate", LocalDate.now().plusDays(10).toString(),
                "startTime", "10:00", "endTime", "12:00", "studentLimit", 20));
        CourseRef course = createCourse(token, request);
        api.post("/api/portal/courses/" + course.id() + "/submit").bearer(token).send().expectStatus(200);
        api.post("/api/admin/courses/" + course.id() + "/decision").bearer(newAdminToken())
                .json(Json.object("decision", "APPROVED")).send().expectStatus(200);
        return course.id();
    }

    private UUID enroll(String token, UUID courseId) {
        return UUID.fromString(api.post("/api/portal/enrollments/courses/" + courseId + "/free").bearer(token)
                .send().expectStatus(201).data().path("id").asText());
    }

    /** Nothing in the API records attendance yet, so the sessions and marks are written directly. */
    private void markAttendance(UUID courseId, UUID enrollmentId, String... marks) {
        for (int i = 0; i < marks.length; i++) {
            UUID session = UUID.randomUUID();
            jdbc.update("""
                    insert into offline_sessions (id, offline_course_id, session_date, starts_at, ends_at)
                    values (?, ?, current_date, now() + make_interval(hours => ?), now() + make_interval(hours => ?))
                    """, session, courseId, i * 3, i * 3 + 2);
            jdbc.update("insert into attendance_records (id, session_id, enrollment_id, status) values (?, ?, ?, ?)",
                    UUID.randomUUID(), session, enrollmentId, marks[i]);
        }
    }

    private static List<String> fieldNames(JsonNode node) {
        List<String> names = new ArrayList<>();
        node.fieldNames().forEachRemaining(names::add);
        return names;
    }

    private static JsonNode actions(JsonNode trail) {
        ArrayNode out = JsonNodeFactory.instance.arrayNode();
        trail.forEach(entry -> out.add(entry.path("action").asText()));
        return out;
    }
}
