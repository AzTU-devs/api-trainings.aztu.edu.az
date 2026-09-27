package com.eduplatform.eduplatform_backend.notification;

import com.eduplatform.eduplatform_backend.support.AbstractIntegrationTest;
import com.eduplatform.eduplatform_backend.support.Json;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Decision notifications are written in the recipient's language. They were English for everyone:
 * the dashboard shows the stored title as it is, so an Azerbaijani expert read English in an
 * Azerbaijani interface, and az applicants got English mail.
 */
class DecisionNotificationLocaleTest extends AbstractIntegrationTest {

    @Test
    void anAzerbaijaniApplicantIsToldInAzerbaijani() {
        TestUser az = applicant("applicant-az", "az");
        TestUser en = applicant("applicant-en", "en");
        String adminToken = newAdminToken();

        decide(adminToken, profileOf(az.id()), "APPROVED");
        decide(adminToken, profileOf(en.id()), "REJECTED");

        Map<String, Object> azNote = latest(az.id());
        assertThat(azNote.get("title")).isEqualTo("Ekspert müraciətiniz təsdiqləndi");
        assertThat((String) azNote.get("body")).contains("ekspert portalına").contains("Administratorun qeydi: ");
        Map<String, Object> enNote = latest(en.id());
        assertThat(enNote.get("title")).isEqualTo("Your expert application was not approved");
        assertThat((String) enNote.get("body")).contains("Note from the reviewer: ");
    }

    @Test
    void anAzerbaijaniTutorHearsAboutTheirCourseInAzerbaijani() {
        TestTutor tutor = newApprovedTutor("course-az");
        jdbc.update("update users set locale = 'az' where id = ?", tutor.userId());
        String tutorToken = login(tutor.email());
        CourseRef course = createCourse(tutorToken, courseRequest("course-az", true));
        submitForReview(tutorToken, course.id());

        api.post("/api/admin/courses/" + course.id() + "/decision").bearer(newAdminToken())
                .json(Json.object("decision", "REJECTED", "note", "Təsviri genişləndirin")).send().expectStatus(200);

        Map<String, Object> note = latest(tutor.userId());
        assertThat(note.get("title")).isEqualTo("“" + course.title() + "” kursunuza düzəlişlər lazımdır");
        assertThat((String) note.get("body")).contains("Təsviri genişləndirin");
    }

    private TestUser applicant(String label, String locale) {
        TestUser user = newUser(label, "USER");
        jdbc.update("update users set locale = ? where id = ?", locale, user.id());
        jdbc.update("insert into tutor_profiles (id, user_id, approval_status) values (?, ?, 'PENDING')",
                UUID.randomUUID(), user.id());
        return user;
    }

    private UUID profileOf(UUID userId) {
        return jdbc.queryForObject("select id from tutor_profiles where user_id = ?", UUID.class, userId);
    }

    private void decide(String adminToken, UUID profileId, String decision) {
        api.post("/api/portal/tutor/admin/" + profileId + "/decision").bearer(adminToken)
                .json(Json.object("decision", decision, "note", "integration test")).send().expectStatus(200);
    }

    /** Written once the decision has committed, before the response is sent. */
    private Map<String, Object> latest(UUID userId) {
        return jdbc.queryForMap(
                "select title, body from notifications where user_id = ? order by created_at desc limit 1", userId);
    }
}
