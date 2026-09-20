package com.eduplatform.eduplatform_backend.course;

import com.eduplatform.eduplatform_backend.support.AbstractIntegrationTest;
import com.eduplatform.eduplatform_backend.support.ApiClient;
import com.eduplatform.eduplatform_backend.support.Json;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * GET /api/public/courses/{slug}. A PUBLISHED or ARCHIVED course is public, paid ones included:
 * archived courses always were, and enrolled students keep opening them from /learn/{slug}, which
 * calls this endpoint without a token. DRAFT, IN_REVIEW and REJECTED courses are served only to a
 * bearer of one of the course's tutors or of an ADMIN/SUPER_ADMIN, because the dashboard opens
 * drafts for editing and IN_REVIEW courses for moderation through this endpoint. Everyone else
 * gets 404 COURSE_NOT_FOUND, never 403, which would confirm the draft exists.
 */
class PublicCourseBySlugTest extends AbstractIntegrationTest {

    @Test
    void aDraftIsNotFoundAnonymously() {
        CourseRef draft = createCourse(login(newApprovedTutor("tutor").email()), courseRequest("draft", true));

        bySlug(draft).send().expectError(404, "COURSE_NOT_FOUND");
    }

    @Test
    void aDraftIsServedToTheTutorWhoOwnsIt() {
        String tutorToken = login(newApprovedTutor("tutor").email());
        CourseRef draft = createCourse(tutorToken, courseRequest("draft", true));

        JsonNode course = bySlug(draft).bearer(tutorToken).send().expectStatus(200).data();

        assertThat(course.path("id").asText()).isEqualTo(draft.id().toString());
        assertThat(course.path("status").asText()).isEqualTo("DRAFT");
    }

    @Test
    void aDraftIsNotFoundForSomeoneWhoDoesNotTeachIt() {
        CourseRef draft = createCourse(login(newApprovedTutor("tutor").email()), courseRequest("draft", true));

        bySlug(draft).bearer(login(newApprovedTutor("stranger").email())).send()
                .expectError(404, "COURSE_NOT_FOUND");
        bySlug(draft).bearer(login(newUser("student", "USER").email())).send()
                .expectError(404, "COURSE_NOT_FOUND");
    }

    /** The roster, not only the one tutor authorised to edit, may open the course. */
    @Test
    void aDraftIsServedToEveryTutorOnItsRoster() {
        TestTutor editor = newApprovedTutor("editor");
        TestTutor coTutor = newApprovedTutor("cotutor");
        CourseRef draft = createCourse(login(editor.email()), courseRequest("draft", true));
        api.put("/api/admin/courses/" + draft.id() + "/tutors").bearer(newAdminToken())
                .json(Json.object("tutorIds", List.of(editor.profileId(), coTutor.profileId()),
                        "authorizedTutorId", editor.profileId()))
                .send().expectStatus(200);

        bySlug(draft).bearer(login(coTutor.email())).send().expectStatus(200);
    }

    @Test
    void aCourseInReviewIsServedToAnAdminForModeration() {
        String tutorToken = login(newApprovedTutor("tutor").email());
        CourseRef course = createCourse(tutorToken, courseRequest("review", true));
        submitForReview(tutorToken, course.id());

        JsonNode served = bySlug(course).bearer(newAdminToken()).send().expectStatus(200).data();
        assertThat(served.path("status").asText()).isEqualTo("IN_REVIEW");

        bySlug(course).send().expectError(404, "COURSE_NOT_FOUND");
    }

    @Test
    void aPublishedCourseIsServedAnonymouslyPaidOnesIncluded() {
        for (boolean free : new boolean[]{true, false}) {
            CourseRef course = publishedCourse(free);

            JsonNode served = bySlug(course).send().expectStatus(200).data();

            assertThat(served.path("status").asText()).isEqualTo("PUBLISHED");
            assertThat(served.path("free").asBoolean()).isEqualTo(free);
        }
    }

    /**
     * Archiving takes a course out of the catalogue, not away from the students already in it. The
     * catalogue is checked before archiving too, so its absence afterwards is not simply a search
     * that would never have found the course.
     */
    @Test
    void anArchivedCourseIsServedAnonymouslyButLeftOutOfTheCatalogue() {
        String tutorToken = login(newApprovedTutor("tutor").email());
        CourseRef course = createCourse(tutorToken, courseRequest("archived", true));
        publish(tutorToken, course.id());
        assertThat(Json.field(catalogueSearch(course), "slug")).as("catalogue before archiving")
                .contains(course.slug());

        api.post("/api/portal/courses/" + course.id() + "/archive").bearer(tutorToken).send().expectStatus(204);

        JsonNode served = bySlug(course).send().expectStatus(200).data();
        assertThat(served.path("id").asText()).isEqualTo(course.id().toString());
        assertThat(served.path("status").asText()).isEqualTo("ARCHIVED");
        assertThat(Json.field(catalogueSearch(course), "slug")).as("catalogue after archiving")
                .doesNotContain(course.slug());
        assertThat(Json.field(api.get("/api/public/courses/search").query("q", course.title()).query("size", 100)
                .send().expectStatus(200).data(), "slug")).as("search alias after archiving")
                .doesNotContain(course.slug());
    }

    /** Opening archived courses to everyone must not open the other unpublished states with them. */
    @Test
    void aRejectedCourseIsNotFoundAnonymouslyButIsServedToItsTutor() {
        String tutorToken = login(newApprovedTutor("tutor").email());
        CourseRef course = createCourse(tutorToken, courseRequest("rejected", true));
        submitForReview(tutorToken, course.id());
        api.post("/api/admin/courses/" + course.id() + "/decision").bearer(newAdminToken())
                .json(Json.object("decision", "REJECTED", "note", "Needs a syllabus"))
                .send().expectStatus(200);

        bySlug(course).send().expectError(404, "COURSE_NOT_FOUND");
        bySlug(course).bearer(login(newUser("student", "USER").email())).send()
                .expectError(404, "COURSE_NOT_FOUND");

        JsonNode served = bySlug(course).bearer(tutorToken).send().expectStatus(200).data();
        assertThat(served.path("status").asText()).isEqualTo("REJECTED");
    }

    private JsonNode catalogueSearch(CourseRef course) {
        return api.get("/api/public/courses").query("q", course.title()).query("size", 100)
                .send().expectStatus(200).data();
    }

    private ApiClient.Call bySlug(CourseRef course) {
        return api.get("/api/public/courses/" + course.slug());
    }
}
