package com.eduplatform.eduplatform_backend.identity;

import com.eduplatform.eduplatform_backend.support.AbstractIntegrationTest;
import com.eduplatform.eduplatform_backend.support.Json;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.eduplatform.eduplatform_backend.support.Json.texts;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Accounts created through the public signup flows must keep the USER role. Both flows go through
 * AuthService.createUserWithUserRolePreHashed, which used to attach the role link to the detached
 * instance that save() had merged away, so the link was never written: the account signed in with
 * no roles and no permissions and got 403 on every student endpoint.
 */
@ExtendWith(OutputCaptureExtension.class)
class SelfRegistrationTest extends AbstractIntegrationTest {

    @Test
    void registeredStudentKeepsTheUserRoleAcrossLoginAndCanEnrolInAFreeCourse() {
        CourseRef course = publishedCourse(true);
        String email = uniqueEmail("student");

        JsonNode registered = api.post("/api/auth/register")
                .json(Json.object("email", email, "password", PASSWORD, "firstName", "New", "lastName", "Student"))
                .send().expectStatus(201).data();
        UUID userId = UUID.fromString(registered.path("user").path("id").asText());
        // The register response used to echo roles [USER] from memory while its permissions,
        // which are read from the database, came back empty.
        assertThat(texts(registered.path("user").path("permissions"))).contains("enrollment:create");
        assertThat(rolesInDb(userId)).containsExactly("USER");

        JsonNode relogin = loginResponse(email, PASSWORD).expectStatus(200).data();
        assertThat(texts(relogin.path("user").path("roles"))).containsExactly("USER");
        assertThat(texts(relogin.path("user").path("permissions"))).isNotEmpty().contains("enrollment:create");

        JsonNode enrollment = api.post("/api/portal/enrollments/courses/" + course.id() + "/free")
                .bearer(relogin.path("accessToken").asText())
                .send().expectStatus(201).data();
        assertThat(enrollment.path("courseId").asText()).isEqualTo(course.id().toString());
        assertThat(enrollment.path("source").asText()).isEqualTo("FREE");
    }

    /**
     * Also pins the mail-disabled contract: the whole message, OTP included, goes to the log,
     * because that line is how an operator reads the admin-bootstrap OTP before SMTP exists.
     */
    @Test
    void tutorSignupPersistsTheUserRoleAndLogsTheOtpWhileMailIsDisabled(CapturedOutput output) {
        UUID categoryId = UUID.randomUUID();
        jdbc.update("insert into categories (id, slug, name) values (?, ?, 'Integration tests')",
                categoryId, unique("category"));
        String email = uniqueEmail("applicant");

        api.post("/api/auth/register/tutor/start")
                .json(Json.object(
                        "email", email, "password", PASSWORD, "firstName", "New", "lastName", "Tutor",
                        "headline", "Teaches integration testing", "categoryIds", List.of(categoryId)))
                .send().expectStatus(202);

        JsonNode result = api.post("/api/auth/register/tutor/verify")
                .json(Json.object("email", email, "otp", otpMailedTo(email, output)))
                .send().expectStatus(201).data();
        assertThat(result.path("approvalStatus").asText()).isEqualTo("PENDING");

        UUID userId = jdbc.queryForObject("select id from users where email = ?", UUID.class, email);
        assertThat(rolesInDb(userId)).containsExactly("USER");
    }

    /** The OTP from the "[mail:disabled]" line MailService writes instead of sending. */
    private static String otpMailedTo(String email, CapturedOutput output) {
        Matcher otp = Pattern.compile("\\[mail:disabled] To <" + Pattern.quote(email)
                + ">[^\\n]*verification code is: (\\d{6})").matcher(output.getAll());
        assertThat(otp.find()).as("an OTP mail to %s in the log", email).isTrue();
        return otp.group(1);
    }
}
