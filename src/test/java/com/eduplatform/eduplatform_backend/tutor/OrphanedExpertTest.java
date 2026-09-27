package com.eduplatform.eduplatform_backend.tutor;

import com.eduplatform.eduplatform_backend.support.AbstractIntegrationTest;
import com.eduplatform.eduplatform_backend.support.Json;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Experts whose account was deleted before deleting an account retired the expert profile with it.
 * Their profile stayed live and pointed at a user no read can load: one such expert turned the
 * whole APPROVED Tutors tab into a 404 for every admin, and the admin view of their course too, so
 * it could not even be opened to hand it to someone else. Production may hold such rows, so the
 * reads cope with them, and V21 retires the ones nothing needs.
 */
class OrphanedExpertTest extends AbstractIntegrationTest {

    private static final String REPAIR = "V21__deleted_accounts_and_input_rules.sql";

    @Test
    void theListsAndTheCourseStillWorkWhenAnExpertsAccountWasDeletedTheOldWay() {
        TestTutor expert = newApprovedTutor("orphan-teaching");
        String tutorToken = login(expert.email());
        CourseRef course = createCourse(tutorToken, courseRequest("orphans-course", true));
        publish(tutorToken, course.id());
        String superToken = login(newUser("super", "SUPER_ADMIN").email());

        deleteAccountTheOldWay(expert.userId());

        JsonNode approved = api.get("/api/portal/tutor/admin").bearer(superToken)
                .query("status", "APPROVED").query("size", 100).send().expectStatus(200).data();
        assertThat(Json.field(approved, "id")).as("an expert without an account is not listed")
                .doesNotContain(expert.profileId().toString());
        JsonNode adminView = api.get("/api/admin/courses/" + course.id()).bearer(superToken).send()
                .expectStatus(200).data();
        assertThat(adminView.path("tutorDisplayName").asText()).isEqualTo("Test orphan-teaching");
        assertThat(adminView.path("tutors")).hasSize(1);
        assertThat(adminView.path("tutors").get(0).path("displayName").asText()).isEqualTo("Test orphan-teaching");
        api.get("/api/admin/courses").bearer(superToken).query("status", "PUBLISHED").query("size", 100)
                .send().expectStatus(200);
        api.get("/api/public/courses/" + course.slug()).send().expectStatus(200);
        // The catalogue fetched every expert's account for the names, and failed the whole page.
        JsonNode catalogue = api.get("/api/public/courses").query("q", course.title()).query("size", 100)
                .send().expectStatus(200).data();
        assertThat(Json.find(catalogue, "id", course.id().toString()).path("tutorDisplayName").asText())
                .isEqualTo("Test orphan-teaching");
        api.get("/api/public/tutors/" + expert.profileId()).send().expectError(404, "TUTOR_NOT_FOUND");
        api.get("/api/admin/analytics/overview").bearer(superToken).send().expectStatus(200);

        // What the admin does next: hand the course to someone else.
        TestTutor successor = newApprovedTutor("successor");
        JsonNode reassigned = api.put("/api/admin/courses/" + course.id() + "/tutors").bearer(superToken)
                .json(Json.object("tutorIds", List.of(successor.profileId()),
                        "authorizedTutorId", successor.profileId()))
                .send().expectStatus(200).data();
        assertThat(reassigned.path("tutorId").asText()).isEqualTo(successor.profileId().toString());
    }

    /** An admin action on the profile of an expert with no account answers 404, not a crash. */
    @Test
    void theProfileOfAnExpertWithoutAnAccountCannotBeDecidedOrEdited() {
        TestTutor expert = newApprovedTutor("orphan-decided");
        deleteAccountTheOldWay(expert.userId());
        String superToken = login(newUser("super", "SUPER_ADMIN").email());

        api.post("/api/portal/tutor/admin/" + expert.profileId() + "/decision").bearer(superToken)
                .json(Json.object("decision", "REJECTED", "note", "gone")).send()
                .expectError(404, "TUTOR_PROFILE_NOT_FOUND");
        api.patch("/api/admin/tutors/" + expert.profileId()).bearer(superToken)
                .json(Json.object("headline", "x")).send().expectError(404, "TUTOR_PROFILE_NOT_FOUND");
    }

    @Test
    void theRepairRetiresTheProfilesNothingNamesAndKeepsTheOnesACourseNeeds() {
        TestTutor named = newApprovedTutor("orphan-named");
        CourseRef course = createCourse(login(named.email()), courseRequest("orphan-named", true));
        TestTutor idle = newApprovedTutor("orphan-idle");
        TestTutor live = newApprovedTutor("still-here");
        deleteAccountTheOldWay(named.userId());
        deleteAccountTheOldWay(idle.userId());

        rerunMigration(REPAIR);
        rerunMigration(REPAIR);   // and it can run again

        assertThat(profileRetired(idle.profileId())).as("retired, as deleting the account does now").isTrue();
        assertThat(profileRetired(named.profileId())).as("the course still names it").isFalse();
        assertThat(profileRetired(live.profileId())).isFalse();
        api.get("/api/admin/courses/" + course.id()).bearer(newAdminToken()).send().expectStatus(200);
    }

    private boolean profileRetired(UUID profileId) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "select deleted_at is not null from tutor_profiles where id = ?", Boolean.class, profileId));
    }
}
