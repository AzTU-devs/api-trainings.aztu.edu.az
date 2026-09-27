package com.eduplatform.eduplatform_backend.tutor;

import com.eduplatform.eduplatform_backend.support.AbstractIntegrationTest;
import com.eduplatform.eduplatform_backend.support.Json;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A category an admin has hidden cannot be newly given to an expert, as it cannot to a course. The
 * expert paths had no such check: the public expert page listed the hidden area, and the category
 * could no longer be deleted while a profile held it.
 */
class ExpertCategoryRulesTest extends AbstractIntegrationTest {

    @Test
    void aHiddenCategoryCannotBeGivenToAnExpertByAnyPath() {
        String adminToken = newAdminToken();
        UUID hidden = category(adminToken, false);
        UUID shown = category(adminToken, true);
        TestTutor expert = newApprovedTutor("expert-areas");
        String expertToken = login(expert.email());

        api.post("/api/portal/tutor/apply").bearer(login(newUser("applicant", "USER").email()))
                .json(Json.object("headline", "Applicant", "categoryIds", List.of(hidden))).send()
                .expectError(400, "CATEGORY_INACTIVE");
        api.patch("/api/portal/tutor/me").bearer(expertToken)
                .json(Json.object("expertiseCategoryIds", List.of(shown, hidden))).send()
                .expectError(400, "CATEGORY_INACTIVE");
        api.patch("/api/admin/tutors/" + expert.profileId()).bearer(adminToken)
                .json(Json.object("expertiseCategoryIds", List.of(hidden))).send()
                .expectError(400, "CATEGORY_INACTIVE");

        api.patch("/api/portal/tutor/me").bearer(expertToken)
                .json(Json.object("expertiseCategoryIds", List.of(shown))).send().expectStatus(200);
        api.delete("/api/admin/categories/" + hidden).bearer(adminToken).send().expectStatus(204);
    }

    /** Hiding a category must not leave every expert filed under it unable to save their profile. */
    @Test
    void aCategoryHiddenAfterItWasGivenIsKept() {
        String adminToken = newAdminToken();
        UUID area = category(adminToken, true);
        TestTutor expert = newApprovedTutor("expert-kept");
        String expertToken = login(expert.email());
        api.patch("/api/portal/tutor/me").bearer(expertToken)
                .json(Json.object("expertiseCategoryIds", List.of(area))).send().expectStatus(200);
        jdbc.update("update categories set is_active = false where id = ?", area);

        api.patch("/api/portal/tutor/me").bearer(expertToken)
                .json(Json.object("headline", "Still saves", "expertiseCategoryIds", List.of(area))).send()
                .expectStatus(200);
        assertThat(jdbc.queryForObject("select count(*) from tutor_expertises where tutor_id = ? and category_id = ?",
                Integer.class, expert.profileId(), area)).isEqualTo(1);
    }

    private UUID category(String adminToken, boolean active) {
        String slug = unique(active ? "shown" : "hidden");
        return UUID.fromString(api.post("/api/admin/categories").bearer(adminToken)
                .json(Json.object("slug", slug, "name", "Area " + slug, "sortOrder", 0, "active", active))
                .send().expectStatus(201).data().path("id").asText());
    }
}
