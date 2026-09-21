package com.eduplatform.eduplatform_backend.enrollment;

import com.eduplatform.eduplatform_backend.support.AbstractIntegrationTest;
import com.eduplatform.eduplatform_backend.support.ApiClient;
import com.eduplatform.eduplatform_backend.support.Json;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The admin participants screen: {@code /api/admin/courses/{courseId}/participants}, behind
 * {@code enrollment:manage}. These endpoints went to production with no test at all.
 *
 * <p>A grant is the sanctioned way into a paid or unpublished course, so it skips the checks
 * the free route applies. Granting twice changes nothing. Removing cancels the enrolment rather
 * than deleting it, and granting again reinstates that same row. enrolled_count counts only the
 * places actually held.
 */
class CourseParticipantAdminTest extends AbstractIntegrationTest {

    @Test
    void anAdminSeatsSomebodyOnAPaidDraftCourseById() {
        CourseRef course = draftCourse(false);
        TestUser student = newUser("student", "USER");

        JsonNode row = add(newAdminToken(), course, Json.object("userId", student.id())).expectStatus(201).data();

        assertThat(row.path("userId").asText()).isEqualTo(student.id().toString());
        assertThat(row.path("email").asText()).isEqualTo(student.email());
        assertThat(row.path("fullName").asText()).isEqualTo("Test student");
        assertThat(row.path("status").asText()).isEqualTo("ACTIVE");
        assertThat(row.path("source").asText()).isEqualTo("ADMIN_GRANT");
        assertThat(enrolmentStatus(student.id(), course.id())).isEqualTo("ACTIVE");
        assertThat(enrolledCount(course)).isEqualTo(1);

        // The participant's own view agrees, which is what lets them open the course.
        JsonNode mine = api.get("/api/portal/enrollments/mine").bearer(login(student.email())).send()
                .expectStatus(200).data();
        assertThat(Json.field(mine, "courseId")).containsExactly(course.id().toString());
    }

    /** The email is typed by hand, so its case must not decide whether the account is found. */
    @Test
    void anAdminSeatsSomebodyByEmailWhateverItsCase() {
        CourseRef course = draftCourse(true);
        TestUser student = newUser("student", "USER");

        JsonNode row = add(newAdminToken(), course,
                Json.object("email", student.email().toUpperCase(Locale.ROOT)))
                .expectStatus(201).data();

        assertThat(row.path("userId").asText()).isEqualTo(student.id().toString());
    }

    @Test
    void aSuperAdminCanManageParticipantsToo() {
        CourseRef course = draftCourse(true);
        TestUser student = newUser("student", "USER");
        String token = login(newUser("super", "SUPER_ADMIN").email());

        add(token, course, Json.object("userId", student.id())).expectStatus(201);
        list(token, course, null).expectStatus(200);
        remove(token, course, student.id()).expectStatus(204);
    }

    @Test
    void grantingAPlaceTwiceChangesNothing() {
        CourseRef course = draftCourse(true);
        TestUser student = newUser("student", "USER");
        String token = newAdminToken();

        String first = add(token, course, Json.object("userId", student.id())).expectStatus(201)
                .data().path("enrollmentId").asText();
        String second = add(token, course, Json.object("email", student.email())).expectStatus(201)
                .data().path("enrollmentId").asText();

        assertThat(second).isEqualTo(first);
        assertThat(enrolmentRows(student.id(), course.id())).isEqualTo(1);
        assertThat(enrolledCount(course)).isEqualTo(1);
    }

    @Test
    void theRosterListsEveryStatusUnlessNarrowed() {
        CourseRef course = draftCourse(true);
        TestUser staying = newUser("staying", "USER");
        TestUser leaving = newUser("leaving", "USER");
        String token = newAdminToken();
        add(token, course, Json.object("userId", staying.id())).expectStatus(201);
        add(token, course, Json.object("userId", leaving.id())).expectStatus(201);
        remove(token, course, leaving.id()).expectStatus(204);

        JsonNode all = list(token, course, null).expectStatus(200).data();
        assertThat(Json.field(all, "userId")).containsExactlyInAnyOrder(staying.id().toString(), leaving.id().toString());
        assertThat(Json.find(all, "userId", leaving.id().toString()).path("status").asText()).isEqualTo("CANCELLED");
        assertThat(Json.find(all, "userId", staying.id().toString()).path("email").asText()).isEqualTo(staying.email());

        JsonNode active = list(token, course, "ACTIVE").expectStatus(200).data();
        assertThat(Json.field(active, "userId")).containsExactly(staying.id().toString());
    }

    /** Progress and certificates hang off the enrolment, so removal cancels it and never deletes it. */
    @Test
    void removingCancelsThePlaceAndGrantingAgainReinstatesTheSameEnrolment() {
        CourseRef course = draftCourse(true);
        TestUser student = newUser("student", "USER");
        String token = newAdminToken();
        String enrolmentId = add(token, course, Json.object("userId", student.id())).expectStatus(201)
                .data().path("enrollmentId").asText();

        remove(token, course, student.id()).expectStatus(204);
        assertThat(enrolmentStatus(student.id(), course.id())).isEqualTo("CANCELLED");
        assertThat(enrolledCount(course)).isZero();

        // Removing somebody already removed is harmless and does not drive the count below the truth.
        remove(token, course, student.id()).expectStatus(204);
        assertThat(enrolledCount(course)).isZero();

        JsonNode back = add(token, course, Json.object("userId", student.id())).expectStatus(201).data();
        assertThat(back.path("enrollmentId").asText()).isEqualTo(enrolmentId);
        assertThat(back.path("status").asText()).isEqualTo("ACTIVE");
        assertThat(enrolmentRows(student.id(), course.id())).isEqualTo(1);
        assertThat(enrolledCount(course)).isEqualTo(1);
    }

    @Test
    void grantsAndRemovalsAreAuditedAgainstTheAdmin() {
        CourseRef course = draftCourse(true);
        TestUser student = newUser("student", "USER");
        TestUser admin = newUser("admin", "ADMIN");
        String token = login(admin.email());

        UUID enrolmentId = UUID.fromString(add(token, course, Json.object("userId", student.id()))
                .expectStatus(201).data().path("enrollmentId").asText());
        remove(token, course, student.id()).expectStatus(204);

        assertThat(jdbc.queryForList("""
                select action from audit_logs
                where entity_type = 'ENROLLMENT' and entity_id = ? and actor_id = ?
                """, String.class, enrolmentId, admin.id())).containsExactlyInAnyOrder("CREATE", "DELETE");
    }

    @Test
    void anEmailWithNoAccountIsItsOwnNotFound() {
        add(newAdminToken(), draftCourse(true), Json.object("email", uniqueEmail("nobody")))
                .expectError(404, "PARTICIPANT_EMAIL_NOT_REGISTERED");
    }

    @Test
    void theRequestMustNameSomebody() {
        CourseRef course = draftCourse(true);
        String token = newAdminToken();

        add(token, course, Json.object()).expectError(400, "PARTICIPANT_IDENTIFIER_REQUIRED");
        add(token, course, Json.object("email", "")).expectError(400, "PARTICIPANT_IDENTIFIER_REQUIRED");
        // @Email runs before the service trims, so padding is refused as malformed, as the
        // dashboard's own email check refuses it; either way nobody is seated.
        add(token, course, Json.object("email", "   ")).expectError(400, "VALIDATION_FAILED");
        add(token, course, Json.object("email", "not-an-email")).expectError(400, "VALIDATION_FAILED");
        add(token, course, Json.object("userId", UUID.randomUUID())).expectError(404, "USER_NOT_FOUND");
    }

    @Test
    void anUnknownCourseOrAnUnenrolledUserIsNotFound() {
        String token = newAdminToken();
        CourseRef nowhere = new CourseRef(UUID.randomUUID(), "nowhere", "Nowhere");
        TestUser student = newUser("student", "USER");

        list(token, nowhere, null).expectError(404, "COURSE_NOT_FOUND");
        add(token, nowhere, Json.object("userId", student.id())).expectError(404, "COURSE_NOT_FOUND");
        remove(token, draftCourse(true), student.id()).expectError(404, "ENROLLMENT_NOT_FOUND");
    }

    /** Only enrollment:manage opens these; a tutor teaching the course does not hold it. */
    @Test
    void tutorsAndStudentsAreRefused() {
        TestTutor tutor = newApprovedTutor("tutor");
        String tutorToken = login(tutor.email());
        CourseRef course = createCourse(tutorToken, courseRequest("own-course", true));
        TestUser student = newUser("student", "USER");
        String studentToken = login(student.email());

        for (String token : new String[]{tutorToken, studentToken}) {
            list(token, course, null).expectStatus(403);
            add(token, course, Json.object("userId", student.id())).expectStatus(403);
            remove(token, course, student.id()).expectStatus(403);
        }
        assertThat(enrolmentRows(student.id(), course.id())).isZero();
    }

    // ── helpers ─────────────────────────────────────────────────────────

    private CourseRef draftCourse(boolean free) {
        return createCourse(login(newApprovedTutor("tutor").email()), courseRequest(free ? "free" : "paid", free));
    }

    private ApiClient.Response add(String token, CourseRef course, Map<String, Object> body) {
        return api.post(path(course)).bearer(token).json(body).send();
    }

    private ApiClient.Response list(String token, CourseRef course, String status) {
        ApiClient.Call call = api.get(path(course)).bearer(token);
        if (status != null) call.query("status", status);
        return call.send();
    }

    private ApiClient.Response remove(String token, CourseRef course, UUID userId) {
        return api.delete(path(course) + "/" + userId).bearer(token).send();
    }

    private static String path(CourseRef course) {
        return "/api/admin/courses/" + course.id() + "/participants";
    }

    private String enrolmentStatus(UUID userId, UUID courseId) {
        return jdbc.queryForObject("select status from enrollments where user_id = ? and course_id = ?",
                String.class, userId, courseId);
    }

    private int enrolmentRows(UUID userId, UUID courseId) {
        return jdbc.queryForObject("select count(*) from enrollments where user_id = ? and course_id = ?",
                Integer.class, userId, courseId);
    }

    private int enrolledCount(CourseRef course) {
        return jdbc.queryForObject("select enrolled_count from courses where id = ?", Integer.class, course.id());
    }
}
