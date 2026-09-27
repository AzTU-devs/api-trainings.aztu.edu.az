package com.eduplatform.eduplatform_backend.audit;

import com.eduplatform.eduplatform_backend.support.AbstractIntegrationTest;
import com.eduplatform.eduplatform_backend.support.Json;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** What the audit trail records, and what it must not record as a change. */
class AuditTrailTest extends AbstractIntegrationTest {

    /**
     * The dashboard sends a price back as a JavaScript number: 50 for a stored 50.00, 0 or 0.0 for a
     * free course. The snapshot kept the scale, so an untouched save was audited as a price change.
     */
    @Test
    void aPriceSentBackWithAnotherScaleIsNotAChange() {
        TestTutor tutor = newApprovedTutor("priced");
        Map<String, Object> paid = courseRequest("priced", false);
        paid.put("price", new BigDecimal("50.00"));
        CourseRef course = createCourse(login(tutor.email()), paid);
        CourseRef free = createCourse(login(tutor.email()), courseRequest("free-priced", true));
        String adminToken = newAdminToken();

        api.patch("/api/admin/courses/" + course.id()).bearer(adminToken)
                .json(Json.object("title", course.title(), "price", 50, "free", false)).send().expectStatus(200);
        api.patch("/api/admin/courses/" + free.id()).bearer(adminToken)
                .json(Json.object("price", 0.0, "free", true)).send().expectStatus(200);

        assertThat(auditRows("COURSE", course.id())).isZero();
        assertThat(auditRows("COURSE", free.id())).isZero();

        api.patch("/api/admin/courses/" + course.id()).bearer(adminToken)
                .json(Json.object("price", 60.5)).send().expectStatus(200);
        assertThat(auditRows("COURSE", course.id())).as("a real change is still recorded").isEqualTo(1);
    }

    /** tag:manage had endpoints but left no trace: creating, renaming or deleting a tag wrote nothing. */
    @Test
    void tagChangesAreAudited() {
        String adminToken = newAdminToken();
        String slug = unique("tag");
        UUID tag = UUID.fromString(api.post("/api/admin/tags").bearer(adminToken)
                .json(Json.object("slug", slug, "name", "Tag " + slug)).send().expectStatus(201)
                .data().path("id").asText());
        api.put("/api/admin/tags/" + tag).bearer(adminToken)
                .json(Json.object("slug", slug, "name", "Renamed " + slug)).send().expectStatus(200);
        api.put("/api/admin/tags/" + tag).bearer(adminToken)
                .json(Json.object("slug", slug, "name", "Renamed " + slug)).send().expectStatus(200);
        api.delete("/api/admin/tags/" + tag).bearer(adminToken).send().expectStatus(204);

        List<String> actions = jdbc.queryForList(
                "select action from audit_logs where entity_type = 'TAG' and entity_id = ? order by occurred_at",
                String.class, tag);
        assertThat(actions).as("the unchanged second save is not a change").containsExactly("CREATE", "UPDATE", "DELETE");
    }

    private int auditRows(String type, UUID id) {
        return jdbc.queryForObject("select count(*) from audit_logs where entity_type = ? and entity_id = ?",
                Integer.class, type, id);
    }
}
