package com.eduplatform.eduplatform_backend.course;

import com.eduplatform.eduplatform_backend.support.AbstractIntegrationTest;
import com.eduplatform.eduplatform_backend.support.Json;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tutors on a course's roster who are not its authorised editor. They were display-only: the
 * course, its counts and its students appeared nowhere in their own dashboard.
 */
class CoTutorTest extends AbstractIntegrationTest {

    @Test
    void aCoTutorSeesTheCourseAndItsStudentsButCannotEditIt() {
        TestTutor editor = newApprovedTutor("editor");
        TestTutor coTutor = newApprovedTutor("co-tutor");
        String editorToken = login(editor.email());
        CourseRef course = createCourse(editorToken, courseRequest("shared", true));
        publish(editorToken, course.id());
        api.put("/api/admin/courses/" + course.id() + "/tutors").bearer(newAdminToken())
                .json(Json.object("tutorIds", List.of(editor.profileId(), coTutor.profileId()),
                        "authorizedTutorId", editor.profileId()))
                .send().expectStatus(200);
        TestUser student = newUser("student", "USER");
        api.post("/api/portal/enrollments/courses/" + course.id() + "/free").bearer(login(student.email())).send()
                .expectStatus(201);
        String coToken = login(coTutor.email());

        assertThat(Json.field(api.get("/api/portal/courses").bearer(coToken).send().expectStatus(200).data(), "id"))
                .contains(course.id().toString());
        assertThat(Json.field(api.get("/api/portal/tutor/students").bearer(coToken).send().expectStatus(200).data(),
                "id")).contains(student.id().toString());
        assertThat(api.get("/api/portal/tutor/dashboard").bearer(coToken).send().expectStatus(200).data()
                .toString()).isNotBlank();

        api.patch("/api/portal/courses/" + course.id()).bearer(coToken).json(Json.object("title", "Mine now"))
                .send().expectError(403, "NOT_COURSE_OWNER");
    }
}
