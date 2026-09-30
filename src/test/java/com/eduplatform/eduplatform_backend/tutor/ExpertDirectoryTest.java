package com.eduplatform.eduplatform_backend.tutor;

import com.eduplatform.eduplatform_backend.support.AbstractIntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The public expert directory, {@code GET /api/public/tutors}: every approved expert with a live
 * account, published course or not, and nobody else.
 */
class ExpertDirectoryTest extends AbstractIntegrationTest {

    private static final String DIRECTORY = "/api/public/tutors";

    @Test
    void listsApprovedExpertsWithoutAnyCourseToAnonymousVisitors() {
        TestTutor approved = newApprovedTutor("directory-approved");
        UUID pending = pendingExpert("directory-pending");

        List<String> ids = allIds();

        assertThat(ids).contains(approved.profileId().toString());
        assertThat(ids).doesNotContain(pending.toString());
    }

    @Test
    void leavesOutExpertsWhoseAccountWasDeleted() {
        TestTutor gone = newApprovedTutor("directory-deleted");
        jdbc.update("update users set deleted_at = now() where id = ?", gone.userId());

        assertThat(allIds()).doesNotContain(gone.profileId().toString());
    }

    @Test
    void exposesTheProfileNotTheAccount() {
        TestTutor expert = newApprovedTutor("directory-fields");

        JsonNode card = null;
        for (JsonNode row : allRows()) {
            if (row.path("id").asText().equals(expert.profileId().toString())) card = row;
        }

        assertThat(card).isNotNull();
        assertThat(card.path("approvalStatus").asText()).isEqualTo("APPROVED");
        assertThat(card.has("customExpertise")).isTrue();
        assertThat(card.has("userId")).isFalse();
        assertThat(card.has("email")).isFalse();
        assertThat(card.toString()).doesNotContain(expert.email());
    }

    /** A caller-chosen sort is ignored, so the order cannot be steered by account fields. */
    @Test
    void ignoresTheCallersSortAndCapsThePageSize() {
        newApprovedTutor("directory-sort");

        JsonNode page = api.get(DIRECTORY).query("sort", "user.passwordHash,desc").query("size", 5000)
                .send().expectStatus(200).data();

        assertThat(page.path("size").asInt()).isEqualTo(100);
        assertThat(page.path("content").isArray()).isTrue();
    }

    private UUID pendingExpert(String label) {
        TestUser user = newUser(label, "USER");
        UUID profileId = UUID.randomUUID();
        jdbc.update("""
                insert into tutor_profiles (id, user_id, headline, approval_status)
                values (?, ?, 'Waiting for approval', 'PENDING')
                """, profileId, user.id());
        return profileId;
    }

    private List<JsonNode> allRows() {
        List<JsonNode> rows = new ArrayList<>();
        for (int page = 0; ; page++) {
            JsonNode data = api.get(DIRECTORY).query("page", page).query("size", 100)
                    .send().expectStatus(200).data();
            data.path("content").forEach(rows::add);
            if (data.path("last").asBoolean(true)) return rows;
        }
    }

    private List<String> allIds() {
        return allRows().stream().map(row -> row.path("id").asText()).toList();
    }
}
