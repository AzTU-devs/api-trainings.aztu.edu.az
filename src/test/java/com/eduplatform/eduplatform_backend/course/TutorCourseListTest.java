package com.eduplatform.eduplatform_backend.course;

import com.eduplatform.eduplatform_backend.support.AbstractIntegrationTest;
import com.eduplatform.eduplatform_backend.support.ApiClient;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.eduplatform.eduplatform_backend.support.Json.field;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * GET /api/portal/courses, the tutor's own course list behind the dashboard's course list and
 * approvals pages, which call it with no q at all. Without q it was a 500 on every call in
 * production: the null search string was bound as bytea and Postgres has no lower(bytea).
 *
 * <p>Each test uses a fresh tutor, so the list holds exactly the courses the test created.
 */
class TutorCourseListTest extends AbstractIntegrationTest {

    @Test
    void listingWithoutAQueryReturnsEveryOwnCourse() {
        Tutor tutor = tutorWithCourses(3);

        JsonNode page = list(tutor.token()).send().expectStatus(200).data();

        assertThat(page.path("totalElements").asLong()).isEqualTo(3);
        assertThat(field(page, "id")).containsExactlyInAnyOrderElementsOf(tutor.courseIds());
    }

    @Test
    void aBlankQueryMeansNoTextFilter() {
        Tutor tutor = tutorWithCourses(3);

        for (String blank : List.of("", "   ")) {
            JsonNode page = list(tutor.token()).query("q", blank).send().expectStatus(200).data();
            assertThat(page.path("totalElements").asLong()).as("q='%s'", blank).isEqualTo(3);
        }
    }

    @Test
    void aStatusFilterWorksWithoutAQuery() {
        Tutor tutor = tutorWithCourses(3);
        String submitted = tutor.courseIds().get(0);
        submitForReview(tutor.token(), UUID.fromString(submitted));

        JsonNode inReview = list(tutor.token()).query("status", "IN_REVIEW").send().expectStatus(200).data();
        JsonNode drafts = list(tutor.token()).query("status", "DRAFT").send().expectStatus(200).data();

        assertThat(field(inReview, "id")).containsExactly(submitted);
        assertThat(field(drafts, "id")).containsExactlyInAnyOrderElementsOf(tutor.courseIds().subList(1, 3));
    }

    /**
     * % and _ are LIKE wildcards and \ is the escape character; a tutor searching for "100%" means
     * the characters.
     */
    @Test
    void likeWildcardsInTheQueryMatchLiterally() {
        TestTutor tutor = newApprovedTutor("tutor");
        String token = login(tutor.email());
        String percent = createTitled(token, "Growth by 100% in a week").id().toString();
        String underscore = createTitled(token, "Naming things in snake_case").id().toString();
        String backslash = createTitled(token, "Windows paths like C:\\temp").id().toString();
        createTitled(token, "Plain title without wildcards");

        assertThat(field(list(token).query("q", "%").send().expectStatus(200).data(), "id"))
                .containsExactly(percent);
        assertThat(field(list(token).query("q", "_").send().expectStatus(200).data(), "id"))
                .containsExactly(underscore);
        assertThat(field(list(token).query("q", "\\").send().expectStatus(200).data(), "id"))
                .containsExactly(backslash);
    }

    /**
     * Paging one row at a time must visit every course exactly once, newest first with id as the
     * tiebreaker. The creation times are forced equal, because the order among ties is exactly
     * what a missing tiebreaker leaves to chance: Postgres may then return one row on two pages
     * and skip another.
     */
    @Test
    void pagingOneAtATimeVisitsEveryCourseOnceNewestFirstWithIdAsTiebreaker() {
        Tutor tutor = tutorWithCourses(4);
        Timestamp sameInstant = Timestamp.from(Instant.parse("2026-01-01T00:00:00Z"));
        for (String id : tutor.courseIds()) {
            jdbc.update("update courses set created_at = ? where id = ?", sameInstant, UUID.fromString(id));
        }
        List<String> expectedOrder = jdbc.queryForList("""
                select c.id::text from courses c join tutor_profiles t on t.id = c.tutor_id
                where t.user_id = ? order by c.created_at desc, c.id desc
                """, String.class, tutor.userId());

        List<String> visited = new ArrayList<>();
        for (int page = 0; page < 4; page++) {
            JsonNode data = list(tutor.token()).query("page", page).query("size", 1).send().expectStatus(200).data();
            assertThat(data.path("totalElements").asLong()).as("totalElements on page %d", page).isEqualTo(4);
            assertThat(data.path("content").size()).as("rows on page %d", page).isEqualTo(1);
            visited.addAll(field(data, "id"));
        }
        JsonNode pastTheEnd = list(tutor.token()).query("page", 4).query("size", 1).send().expectStatus(200).data();

        assertThat(visited).containsExactlyElementsOf(expectedOrder);
        assertThat(pastTheEnd.path("content").size()).isZero();
    }

    // ── helpers ─────────────────────────────────────────────────────────

    private record Tutor(UUID userId, String token, List<String> courseIds) {}

    /** A fresh tutor with {@code count} DRAFT courses; ids in creation order. */
    private Tutor tutorWithCourses(int count) {
        TestTutor tutor = newApprovedTutor("tutor");
        String token = login(tutor.email());
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            ids.add(createCourse(token, courseRequest("listed", true)).id().toString());
        }
        return new Tutor(tutor.userId(), token, ids);
    }

    private CourseRef createTitled(String token, String title) {
        Map<String, Object> request = courseRequest("titled", true);
        request.put("title", title);
        return createCourse(token, request);
    }

    private ApiClient.Call list(String token) {
        return api.get("/api/portal/courses").bearer(token);
    }
}
