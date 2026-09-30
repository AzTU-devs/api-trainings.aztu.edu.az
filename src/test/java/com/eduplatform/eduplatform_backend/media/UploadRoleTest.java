package com.eduplatform.eduplatform_backend.media;

import com.eduplatform.eduplatform_backend.support.AbstractIntegrationTest;
import com.eduplatform.eduplatform_backend.support.TestFiles;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Who may upload. POST /api/media takes up to 210 MB, and Tomcat writes a multipart body to disk
 * before any controller runs, so the endpoint is closed to participant-only accounts in the security
 * filter chain itself (SecurityConfig), where the refusal comes before the body is read. Nothing a
 * participant does uploads a file.
 */
class UploadRoleTest extends AbstractIntegrationTest {

    @Test
    void aParticipantOnlyAccountCannotUploadAndNothingIsStored() {
        TestUser learner = newUser("learner", "USER");

        upload(login(learner.email()), "photo.png", "image/png", TestFiles.png()).expectError(403, "FORBIDDEN");

        assertThat(mediaOwnedBy(learner.id())).isZero();
    }

    /** An applicant holds USER until approved, and has nothing to upload before that. */
    @Test
    void aPendingExpertCannotUploadEither() {
        TestUser applicant = newUser("applicant", "USER");
        jdbc.update("insert into tutor_profiles (id, user_id, headline, approval_status) values (?, ?, 'Pending', 'PENDING')",
                UUID.randomUUID(), applicant.id());

        upload(login(applicant.email()), "me.png", "image/png", TestFiles.png()).expectError(403, "FORBIDDEN");
    }

    @Test
    void anonymousCallersAreStillAskedToSignIn() {
        api.post("/api/media").file("file", "photo.png", "image/png", TestFiles.png()).send().expectStatus(401);
    }

    @Test
    void tutorsAndStaffUpload() {
        uploadMedia(login(newApprovedTutor("tutor").email()), "a.png", "image/png", TestFiles.png());
        uploadMedia(newAdminToken(), "b.pdf", "application/pdf", TestFiles.pdf());
        uploadMedia(login(newUser("super", "SUPER_ADMIN").email()), "c.png", "image/png", TestFiles.png());
    }

    private Integer mediaOwnedBy(UUID userId) {
        return jdbc.queryForObject("select count(*) from media_files where owner_user_id = ?", Integer.class, userId);
    }
}
