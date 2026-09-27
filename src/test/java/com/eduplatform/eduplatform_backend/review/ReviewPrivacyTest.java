package com.eduplatform.eduplatform_backend.review;

import com.eduplatform.eduplatform_backend.support.AbstractIntegrationTest;
import com.eduplatform.eduplatform_backend.support.Json;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The public review list gave every anonymous caller the internal account id of every reviewer,
 * which the site never showed and which tied a person's reviews together across courses. The
 * field stays in the response, as null, so the shape the public site reads is unchanged.
 */
class ReviewPrivacyTest extends AbstractIntegrationTest {

    @Test
    void thePublicListNamesTheReviewerButDoesNotIdentifyTheirAccount() {
        CourseRef course = publishedCourse(true);
        TestUser reviewer = newUser("private-reviewer", "USER");
        String token = login(reviewer.email());
        api.post("/api/portal/enrollments/courses/" + course.id() + "/free").bearer(token).send().expectStatus(201);
        JsonNode written = api.post("/api/portal/courses/" + course.id() + "/reviews").bearer(token)
                .json(Json.object("rating", 4, "title", "Good", "body", "Useful course"))
                .send().expectStatus(201).data();
        assertThat(written.path("userId").asText()).as("the author's own review keeps it")
                .isEqualTo(reviewer.id().toString());
        assertThat(written.path("authorName").asText()).isEqualTo("Test private-reviewer");

        JsonNode page = api.get("/api/public/courses/" + course.id() + "/reviews").send().expectStatus(200).data();
        JsonNode listed = page.path("content").get(0);

        assertThat(listed.has("userId")).isTrue();
        assertThat(listed.path("userId").isNull()).isTrue();
        assertThat(listed.path("authorName").asText()).isEqualTo("Test private-reviewer");
        assertThat(page.toString()).doesNotContain(reviewer.id().toString());

        JsonNode moderated = api.get("/api/admin/reviews").bearer(newAdminToken()).query("courseId", course.id())
                .send().expectStatus(200).data();
        assertThat(Json.field(moderated, "userId")).as("moderators still see who wrote it")
                .contains(reviewer.id().toString());
    }
}
