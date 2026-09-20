package com.eduplatform.eduplatform_backend.course;

import com.eduplatform.eduplatform_backend.support.AbstractIntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static com.eduplatform.eduplatform_backend.support.Json.field;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * GET /api/public/courses in free-only mode (app.payments.enabled=false, as in production). With
 * no payment provider a paid course leads to a checkout that cannot charge, so the catalogue
 * serves free courses only, whatever the caller asks for.
 */
class PublicCatalogTest extends AbstractIntegrationTest {

    /** It used to be rewritten to free=true, answering "paid courses only" with every free course. */
    @Test
    void anExplicitRequestForPaidCoursesIsAnEmptyPage() {
        publishedCourse(false);
        publishedCourse(true);

        JsonNode page = api.get("/api/public/courses").query("free", false).query("size", 100)
                .send().expectStatus(200).data();

        assertThat(page.path("totalElements").asLong()).isZero();
        assertThat(page.path("content").size()).isZero();
    }

    @Test
    void theDefaultListingLeavesPaidCoursesOut() {
        CourseRef paid = publishedCourse(false);
        CourseRef free = publishedCourse(true);

        JsonNode listing = api.get("/api/public/courses").query("size", 100).send().expectStatus(200).data();
        assertThat(field(listing, "free")).containsOnly("true");
        assertThat(field(listing, "slug")).doesNotContain(paid.slug());

        // Narrowed by title as well, so the check holds however many courses other tests published.
        assertThat(field(searchByQ(paid.title()), "slug")).doesNotContain(paid.slug());
        assertThat(field(searchByQ(free.title()), "slug")).contains(free.slug());
        assertThat(field(api.get("/api/public/courses/search").query("q", paid.title())
                .send().expectStatus(200).data(), "slug")).doesNotContain(paid.slug());
    }

    /**
     * Whole words go through the full-text index; a partial word falls back to substring matching,
     * which used to cover the title only. The distinctive word here is in the subtitle alone.
     */
    @Test
    void aPartialWordFromTheSubtitleFindsTheCourse() {
        String token = login(newApprovedTutor("tutor").email());
        String distinctive = word();
        Map<String, Object> request = courseRequest("subtitled", true);
        request.put("title", "Cluster operations");
        request.put("subtitle", "Site " + distinctive + " engineering");
        CourseRef course = createCourse(token, request);
        publish(token, course.id());

        String partial = distinctive.substring(0, distinctive.length() - 4);
        assertThat(field(searchByQ(partial), "slug")).as("q=%s", partial).contains(course.slug());
        assertThat(field(searchByQ(distinctive), "slug")).as("q=%s", distinctive).contains(course.slug());
    }

    private JsonNode searchByQ(String q) {
        return api.get("/api/public/courses").query("q", q).query("size", 100).send().expectStatus(200).data();
    }
}
