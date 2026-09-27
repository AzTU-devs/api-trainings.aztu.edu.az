package com.eduplatform.eduplatform_backend.catalog;

import com.eduplatform.eduplatform_backend.support.AbstractIntegrationTest;
import com.eduplatform.eduplatform_backend.support.ApiClient;
import com.eduplatform.eduplatform_backend.support.Json;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Categories: what deleting and hiding one does, and whose list shows what. */
class CategoryRulesTest extends AbstractIntegrationTest {

    /** Deleting a category in use silently stripped it from published courses and orphaned its children. */
    @Test
    void aCategoryInUseOrWithChildrenCannotBeDeleted() {
        String adminToken = newAdminToken();
        UUID used = id(create(adminToken, unique("used"), null).expectStatus(201));
        UUID parent = id(create(adminToken, unique("parent"), null).expectStatus(201));
        create(adminToken, unique("child"), parent).expectStatus(201);
        Map<String, Object> request = courseRequest("categorised", true);
        request.put("categoryIds", List.of(used));
        createCourse(login(newApprovedTutor("tutor").email()), request);

        api.delete("/api/admin/categories/" + used).bearer(adminToken).send().expectError(409, "CATEGORY_IN_USE");
        api.delete("/api/admin/categories/" + parent).bearer(adminToken).send()
                .expectError(409, "CATEGORY_HAS_CHILDREN");
    }

    /** The slug of a deleted category was held for ever by the full unique constraint. */
    @Test
    void aDeletedCategorysSlugCanBeUsedAgain() {
        String adminToken = newAdminToken();
        String slug = unique("reused");
        UUID first = id(create(adminToken, slug, null).expectStatus(201));
        api.delete("/api/admin/categories/" + first).bearer(adminToken).send().expectStatus(204);

        create(adminToken, slug, null).expectStatus(201);
    }

    @Test
    void hiddenCategoriesAreLeftOffThePublicListButNotTheAdminOne() {
        String adminToken = newAdminToken();
        String slug = unique("hidden");
        UUID hidden = id(api.post("/api/admin/categories").bearer(adminToken)
                .json(Json.object("slug", slug, "name", "Hidden " + slug, "sortOrder", 0, "active", false))
                .send().expectStatus(201));
        UUID parent = id(create(adminToken, unique("tree"), null).expectStatus(201));
        UUID child = id(create(adminToken, unique("leaf"), parent).expectStatus(201));

        List<String> publicIds = ids(api.get("/api/public/categories").send().expectStatus(200).data());
        assertThat(publicIds).doesNotContain(hidden.toString());

        JsonNode all = api.get("/api/admin/categories").bearer(adminToken).send().expectStatus(200).data();
        assertThat(ids(all)).contains(hidden.toString(), parent.toString(), child.toString());
        for (JsonNode c : all) {
            if (child.toString().equals(c.path("id").asText())) {
                assertThat(c.path("parentId").asText()).isEqualTo(parent.toString());
            }
        }
    }

    @Test
    void aCategoryCannotBePlacedUnderItsOwnChild() {
        String adminToken = newAdminToken();
        String parentSlug = unique("loop-parent");
        UUID parent = id(create(adminToken, parentSlug, null).expectStatus(201));
        UUID child = id(create(adminToken, unique("loop-child"), parent).expectStatus(201));

        api.put("/api/admin/categories/" + parent).bearer(adminToken)
                .json(Json.object("slug", parentSlug, "name", "Loop", "sortOrder", 0, "parentId", child))
                .send().expectError(400, "CATEGORY_CYCLE");
    }

    private ApiClient.Response create(String token, String slug, UUID parentId) {
        return api.post("/api/admin/categories").bearer(token)
                .json(Json.object("slug", slug, "name", "Category " + slug, "sortOrder", 0, "parentId", parentId))
                .send();
    }

    private static UUID id(ApiClient.Response created) {
        return UUID.fromString(created.data().path("id").asText());
    }

    private static List<String> ids(JsonNode array) {
        List<String> out = new java.util.ArrayList<>();
        array.forEach(n -> out.add(n.path("id").asText()));
        return out;
    }
}
