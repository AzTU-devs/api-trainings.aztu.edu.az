package com.eduplatform.eduplatform_backend.common;

import com.eduplatform.eduplatform_backend.support.AbstractIntegrationTest;
import com.eduplatform.eduplatform_backend.support.ApiClient;
import com.eduplatform.eduplatform_backend.support.Json;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * A null inside a list of ids is the caller's mistake and a 400. It used to reach findById, which
 * refuses a null id with InvalidDataAccessApiUsageException — a 500 and an ERROR "Unhandled
 * exception" on every course create and edit path, the only 5xx a whole regression sweep found.
 */
class NullIdElementTest extends AbstractIntegrationTest {

    private static final List<Object> WITH_NULL = Arrays.asList((Object) null);

    @Test
    void courseCategoryTagAndTutorListsRefuseANullElement() {
        TestTutor tutor = newApprovedTutor("null-ids");
        String tutorToken = login(tutor.email());
        String adminToken = newAdminToken();
        CourseRef course = createCourse(tutorToken, courseRequest("null-ids", true));

        Map<String, Object> create = courseRequest("null-cat", true);
        create.put("categoryIds", WITH_NULL);
        api.post("/api/portal/courses").bearer(tutorToken).json(create).send().expectStatus(400);
        patch(tutorToken, "/api/portal/courses/" + course.id(), "categoryIds").expectStatus(400);
        patch(tutorToken, "/api/portal/courses/" + course.id(), "tagIds").expectStatus(400);
        patch(adminToken, "/api/admin/courses/" + course.id(), "categoryIds").expectStatus(400);

        Map<String, Object> adminCreate = Json.object("course", courseRequest("null-tutor", true),
                "tutorIds", withNullAnd(tutor.profileId()), "authorizedTutorId", tutor.profileId());
        api.post("/api/admin/courses").bearer(adminToken).json(adminCreate).send().expectStatus(400);
        api.put("/api/admin/courses/" + course.id() + "/tutors").bearer(adminToken)
                .json(Json.object("tutorIds", withNullAnd(tutor.profileId()), "authorizedTutorId", tutor.profileId()))
                .send().expectStatus(400);
    }

    @Test
    void theOtherIdListsRefuseANullElementToo() {
        String superToken = login(newUser("super", "SUPER_ADMIN").email());
        TestTutor expert = newApprovedTutor("null-areas");

        api.patch("/api/portal/tutor/me").bearer(login(expert.email()))
                .json(Json.object("expertiseCategoryIds", WITH_NULL)).send().expectStatus(400);
        api.post("/api/admin/users").bearer(superToken)
                .json(Json.object("email", uniqueEmail("null-role"), "fullName", "Null Role",
                        "password", "Temporary12345", "roles", WITH_NULL))
                .send().expectStatus(400);
        api.post("/api/admin/notifications/broadcast").bearer(superToken)
                .json(Json.object("title", "t", "body", "b", "target", "USERS", "userIds", WITH_NULL))
                .send().expectStatus(400);
        api.post("/api/admin/rooms").bearer(superToken)
                .json(Json.object("name", "Room " + word(), "roomNumber", word(), "capacity", 10,
                        "hourlyRate", 10, "currency", "AZN", "imageMediaIds", Arrays.asList(null, null)))
                .send().expectStatus(400);
    }

    private ApiClient.Response patch(String token, String path, String field) {
        return api.patch(path).bearer(token).json(Json.object(field, WITH_NULL)).send();
    }

    private static List<Object> withNullAnd(UUID id) {
        List<Object> ids = new ArrayList<>();
        ids.add(id);
        ids.add(null);
        return ids;
    }
}
