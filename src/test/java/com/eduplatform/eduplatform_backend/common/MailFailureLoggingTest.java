package com.eduplatform.eduplatform_backend.common;

import com.eduplatform.eduplatform_backend.support.AbstractIntegrationTest;
import com.eduplatform.eduplatform_backend.support.Json;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.test.context.TestPropertySource;

import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Mail switched on, as in production, with the SMTP server unreachable. A failed send must log the
 * recipient and subject only: the body carries live password-reset links and OTPs, and failure
 * logs are exactly what piles up while SMTP is down. The mail-disabled case, where the body is
 * logged on purpose, is covered by SelfRegistrationTest.
 */
@ExtendWith(OutputCaptureExtension.class)
@TestPropertySource(properties = {
        "app.mail.enabled=true",
        // Nothing listens on port 1, so every send fails at connect, immediately, and never reaches
        // a real mailbox whatever MAIL_* settings a developer's .env holds.
        "spring.mail.host=127.0.0.1",
        "spring.mail.port=1",
})
class MailFailureLoggingTest extends AbstractIntegrationTest {

    @Autowired
    private JavaMailSender mailSender;

    @Test
    void aFailedSendLogsRecipientAndSubjectButNeverTheBody(CapturedOutput output) {
        TestUser user = newUser("forgetful", "USER");

        api.post("/api/auth/password/forgot").json(Json.object("email", user.email())).send().expectStatus(202);

        // A reset token was really minted, so the body had a live link in it to leak.
        assertThat(jdbc.queryForObject(
                "select count(*) from auth_action_tokens where user_id = ? and purpose = 'PASSWORD_RESET'",
                Integer.class, user.id())).isEqualTo(1);
        assertThat(output.getAll())
                .contains(user.email())
                .contains("Reset your AzTU EduPlatform password")
                .doesNotContain("reset-password?token=")
                .doesNotContain("We received a request to reset your password");
    }

    /**
     * The aggregate /actuator/health is anonymous. With the mail indicator on, every call logged in
     * to SMTP (a real Gmail login in production, which can get the account throttled) and reported
     * DOWN whenever SMTP was unreachable. Readiness never included mail.
     */
    @Test
    void anonymousHealthDoesNotProbeSmtp() {
        String status = api.get("/actuator/health").send().expectStatus(200).json().path("status").asText();

        assertThat(status).isEqualTo("UP");
    }

    /** JavaMail waits forever by default, holding a request thread and, inside a transaction, a DB connection. */
    @Test
    void everySmtpStepIsTimeBounded() {
        Properties smtp = ((JavaMailSenderImpl) mailSender).getJavaMailProperties();

        assertThat(smtp.getProperty("mail.smtp.connectiontimeout")).isEqualTo("5000");
        assertThat(smtp.getProperty("mail.smtp.timeout")).isEqualTo("10000");
        assertThat(smtp.getProperty("mail.smtp.writetimeout")).isEqualTo("10000");
    }
}
