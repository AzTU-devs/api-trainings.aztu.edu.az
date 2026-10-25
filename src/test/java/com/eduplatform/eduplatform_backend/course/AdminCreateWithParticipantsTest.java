package com.eduplatform.eduplatform_backend.course;

import com.eduplatform.eduplatform_backend.support.AbstractIntegrationTest;
import com.eduplatform.eduplatform_backend.support.ApiClient;
import com.eduplatform.eduplatform_backend.support.Json;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * POST /api/admin/courses with {@code participantUserIds}: a training created together with its
 * tutors and its participants. Each participant gets exactly the place the participants screen's
 * "add participant" gives (an ADMIN_GRANT, counted, past the seat limit if need be), in the same
 * transaction as the course, so an unknown account leaves nothing behind.
 */
class AdminCreateWithParticipantsTest extends AbstractIntegrationTest {

    private static final LocalDate DAY = LocalDate.now().plusDays(50);

    @Test
    void aOneTimeTrainingIsCreatedWithItsTutorsAndParticipants() {
        TestTutor editor = newApprovedTutor("editor");
        TestTutor coTutor = newApprovedTutor("co-tutor");
        TestUser first = newUser("first", "USER");
        TestUser second = newUser("second", "USER");
        TestUser third = newUser("third", "USER");

        JsonNode course = create(oneTimeRequest(20), List.of(editor, coTutor), editor,
                List.of(first.id(), second.id(), third.id())).expectStatus(201).data();
        UUID id = UUID.fromString(course.path("id").asText());

        assertThat(course.path("status").asText()).as("still a draft").isEqualTo("DRAFT");
        assertThat(course.path("courseType").asText()).isEqualTo("ONE_TIME");
        assertThat(course.path("tutors").findValuesAsText("tutorId"))
                .containsExactlyInAnyOrder(editor.profileId().toString(), coTutor.profileId().toString());
        assertThat(course.path("enrolledCount").asInt()).isEqualTo(3);
        assertThat(course.path("offlineDetails").path("enrolledCount").asInt()).as("seats taken").isEqualTo(3);
        assertThat(jdbc.queryForObject("select enrolled_count from courses where id = ?", Integer.class, id)).isEqualTo(3);
        for (TestUser user : List.of(first, second, third)) {
            assertThat(jdbc.queryForMap("select status, source from enrollments where user_id = ? and course_id = ?",
                    user.id(), id)).containsEntry("status", "ACTIVE").containsEntry("source", "ADMIN_GRANT");
        }
        // Each grant is audited as the participants screen audits it.
        assertThat(jdbc.queryForObject("""
                select count(*) from audit_logs
                 where entity_type = 'ENROLLMENT' and action = 'CREATE' and after_data ->> 'courseId' = ?
                """, Integer.class, id.toString())).isEqualTo(3);

        // The participants screen shows them, and a participant can see the course among their own.
        JsonNode roster = api.get("/api/admin/courses/" + id + "/participants").bearer(newAdminToken())
                .send().expectStatus(200).data();
        assertThat(Json.field(roster, "userId"))
                .containsExactlyInAnyOrder(first.id().toString(), second.id().toString(), third.id().toString());
        assertThat(Json.field(api.get("/api/portal/enrollments/mine").bearer(login(first.email())).send()
                .expectStatus(200).data(), "courseId")).contains(id.toString());
    }

    @Test
    void aRepeatedIdIsSeatedOnce() {
        TestTutor tutor = newApprovedTutor("tutor");
        TestUser student = newUser("student", "USER");
        TestUser other = newUser("other", "USER");

        JsonNode course = create(oneTimeRequest(20), List.of(tutor), tutor,
                List.of(student.id(), other.id(), student.id(), student.id())).expectStatus(201).data();

        assertThat(course.path("enrolledCount").asInt()).isEqualTo(2);
        assertThat(jdbc.queryForObject("select count(*) from enrollments where course_id = ?::uuid",
                Integer.class, course.path("id").asText())).isEqualTo(2);
    }

    /** As the participants screen allows: the admin has decided the room can take them. */
    @Test
    void participantsMayGoPastTheSeatLimit() {
        TestTutor tutor = newApprovedTutor("tutor");

        JsonNode course = create(oneTimeRequest(1), List.of(tutor), tutor,
                List.of(newUser("a", "USER").id(), newUser("b", "USER").id())).expectStatus(201).data();

        assertThat(course.path("enrolledCount").asInt()).isEqualTo(2);
        assertThat(course.path("offlineDetails").path("studentLimit").asInt()).isEqualTo(1);
        assertThat(course.path("offlineDetails").path("enrolledCount").asInt()).isEqualTo(2);
    }

    @Test
    void anyCourseTypeTakesParticipants() {
        TestTutor tutor = newApprovedTutor("tutor");
        TestUser student = newUser("student", "USER");

        JsonNode course = create(courseRequest("online-with-people", false), List.of(tutor), tutor,
                List.of(student.id())).expectStatus(201).data();

        assertThat(course.path("courseType").asText()).isEqualTo("ONLINE");
        assertThat(course.path("enrolledCount").asInt()).isEqualTo(1);
    }

    @Test
    void anUnknownUserIsRefusedAndNothingIsCreated() {
        TestTutor tutor = newApprovedTutor("tutor");
        TestUser student = newUser("student", "USER");
        UUID ghost = UUID.randomUUID();
        Map<String, Object> request = oneTimeRequest(20);

        ApiClient.Response response = create(request, List.of(tutor), tutor, List.of(student.id(), ghost))
                .expectError(400, "USER_NOT_FOUND");

        assertThat(response.json().path("message").asText()).contains(ghost.toString());
        assertNothingCreated(request, student);
    }

    @Test
    void aDeletedUserIsRefusedAndNothingIsCreated() {
        TestTutor tutor = newApprovedTutor("tutor");
        TestUser student = newUser("student", "USER");
        TestUser gone = newUser("gone", "USER");
        deleteAccountTheOldWay(gone.id());
        Map<String, Object> request = oneTimeRequest(20);

        ApiClient.Response response = create(request, List.of(tutor), tutor, List.of(student.id(), gone.id()))
                .expectError(400, "USER_NOT_FOUND");

        assertThat(response.json().path("message").asText()).contains(gone.id().toString());
        assertNothingCreated(request, student);
    }

    @Test
    void atMost500ParticipantsAreAccepted() {
        TestTutor tutor = newApprovedTutor("tutor");
        List<UUID> many = new ArrayList<>();
        for (int i = 0; i < 501; i++) many.add(UUID.randomUUID());

        create(oneTimeRequest(20), List.of(tutor), tutor, many).expectError(400, "VALIDATION_FAILED");
        List<Object> withNull = new ArrayList<>();
        withNull.add(null);
        api.post("/api/admin/courses").bearer(newAdminToken())
                .json(Json.object("course", oneTimeRequest(20), "tutorIds", List.of(tutor.profileId()),
                        "authorizedTutorId", tutor.profileId(), "participantUserIds", withNull))
                .send().expectError(400, "VALIDATION_FAILED");
    }

    @Test
    void withoutParticipantsTheCourseHasNone() {
        TestTutor tutor = newApprovedTutor("tutor");

        JsonNode course = create(oneTimeRequest(20), List.of(tutor), tutor, List.of()).expectStatus(201).data();

        assertThat(course.path("enrolledCount").asInt()).isZero();
        assertThat(course.path("offlineDetails").path("enrolledCount").asInt()).isZero();
    }

    // ── helpers ─────────────────────────────────────────────────────────

    private ApiClient.Response create(Map<String, Object> course, List<TestTutor> tutors, TestTutor editor,
                                      List<UUID> participants) {
        return api.post("/api/admin/courses").bearer(newAdminToken())
                .json(Json.object(
                        "course", course,
                        "tutorIds", tutors.stream().map(TestTutor::profileId).toList(),
                        "authorizedTutorId", editor.profileId(),
                        "participantUserIds", participants))
                .send();
    }

    private static Map<String, Object> oneTimeRequest(int seats) {
        Map<String, Object> request = courseRequest("one-time-people", true);
        request.put("courseType", "ONE_TIME");
        request.remove("onlineDetails");
        request.put("offlineDetails", Json.object("startDate", DAY.toString(), "startTime", "10:00",
                "endTime", "12:00", "studentLimit", seats));
        return request;
    }

    private void assertNothingCreated(Map<String, Object> request, TestUser student) {
        assertThat(jdbc.queryForObject("select count(*) from courses where slug = ?", Integer.class,
                request.get("slug"))).as("the course rolled back").isZero();
        assertThat(jdbc.queryForObject("select count(*) from enrollments where user_id = ?", Integer.class,
                student.id())).as("nobody was seated").isZero();
    }
}
