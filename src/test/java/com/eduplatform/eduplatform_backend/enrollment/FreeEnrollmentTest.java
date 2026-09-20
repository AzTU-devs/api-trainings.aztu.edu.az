package com.eduplatform.eduplatform_backend.enrollment;

import com.eduplatform.eduplatform_backend.support.AbstractIntegrationTest;
import com.eduplatform.eduplatform_backend.support.ApiClient;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * POST /api/portal/enrollments/courses/{id}/free and the student's own enrollment list. The free
 * route is the only way in while payments are disabled, so it must not open paid courses: the
 * public slug endpoint hands out a paid course's id to anyone.
 */
class FreeEnrollmentTest extends AbstractIntegrationTest {

    @Test
    void freeEnrollmentInAPaidCourseIsRefusedWithPaymentRequired() {
        CourseRef paid = publishedCourse(false);
        TestUser student = newUser("student", "USER");

        enrolFree(login(student.email()), paid.id()).expectError(422, "PAYMENT_REQUIRED");

        assertThat(enrollmentsOf(student.id(), paid.id())).as("enrollment rows").isZero();
        assertThat(jdbc.queryForObject("select enrolled_count from courses where id = ?", Integer.class, paid.id()))
                .as("enrolled_count").isZero();
    }

    @Test
    void freeEnrollmentInACourseThatIsNotPublishedIsRefused() {
        TestTutor tutor = newApprovedTutor("tutor");
        CourseRef draft = createCourse(login(tutor.email()), courseRequest("draft", true));
        TestUser student = newUser("student", "USER");

        enrolFree(login(student.email()), draft.id()).expectError(409, "COURSE_NOT_PUBLISHED");

        assertThat(enrollmentsOf(student.id(), draft.id())).as("enrollment rows").isZero();
    }

    /**
     * The mapper reads the course title after the transaction has closed, so a lazily loaded
     * course made every call with at least one enrollment a 500.
     */
    @Test
    void myEnrollmentsListCarriesTheCourseTitle() {
        CourseRef course = publishedCourse(true);
        String token = login(newUser("student", "USER").email());
        enrolFree(token, course.id()).expectStatus(201);

        JsonNode page = api.get("/api/portal/enrollments/mine").bearer(token).send().expectStatus(200).data();

        assertThat(page.path("totalElements").asLong()).isEqualTo(1);
        JsonNode enrollment = page.path("content").get(0);
        assertThat(enrollment.path("courseId").asText()).isEqualTo(course.id().toString());
        assertThat(enrollment.path("courseTitle").asText()).isEqualTo(course.title());
    }

    private ApiClient.Response enrolFree(String token, UUID courseId) {
        return api.post("/api/portal/enrollments/courses/" + courseId + "/free").bearer(token).send();
    }

    private int enrollmentsOf(UUID userId, UUID courseId) {
        return jdbc.queryForObject("select count(*) from enrollments where user_id = ? and course_id = ?",
                Integer.class, userId, courseId);
    }
}
