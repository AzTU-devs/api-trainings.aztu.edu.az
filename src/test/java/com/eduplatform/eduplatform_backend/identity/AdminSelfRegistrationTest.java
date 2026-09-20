package com.eduplatform.eduplatform_backend.identity;

import com.eduplatform.eduplatform_backend.common.security.TokenHasher;
import com.eduplatform.eduplatform_backend.support.AbstractIntegrationTest;
import com.eduplatform.eduplatform_backend.support.Json;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;

import java.util.Locale;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Admin self-registration is bootstrap-only: open while app.security.admin-self-register-enabled
 * is true AND no ADMIN/SUPER_ADMIN exists. The base configuration turns the flag on and the dev
 * seeds provide admins, which is exactly the state in which the flag alone used to let anyone with
 * a mailbox become ADMIN.
 */
class AdminSelfRegistrationTest extends AbstractIntegrationTest {

    @Value("${app.security.admin-self-register-enabled}")
    private boolean selfRegisterFlag;

    @BeforeEach
    void flagIsOnAndAnAdminExists() {
        assertThat(selfRegisterFlag).as("app.security.admin-self-register-enabled").isTrue();
        Integer admins = jdbc.queryForObject("""
                select count(*) from user_roles ur join roles r on r.id = ur.role_id
                where r.code in ('ADMIN', 'SUPER_ADMIN')
                """, Integer.class);
        assertThat(admins).as("admins seeded by db/dev").isPositive();
    }

    @Test
    void startIsClosedOnceAnAdminExistsEvenWithTheFlagOn() {
        String email = uniqueEmail("would-be-admin");

        api.post("/api/auth/admin/register/start")
                .json(Json.object(
                        "firstName", "Would", "lastName", "Be", "email", email,
                        "phoneNumber", "+994501234567", "finKod", finKod(), "password", "Str0ngPassword"))
                .send().expectError(403, "ADMIN_REGISTER_CLOSED");

        assertThat(jdbc.queryForObject("select count(*) from admin_registration_otps where email = ?",
                Integer.class, email)).as("OTPs issued").isZero();
    }

    /**
     * An OTP issued while bootstrap was still open, verified after the first admin exists. Guarding
     * only the start step would let it mint a second admin.
     */
    @Test
    void verifyCannotMintAnAdminOnceOneExistsEvenWithAPendingOtp() {
        String email = uniqueEmail("late-admin");
        String otp = "482913";
        jdbc.update("""
                insert into admin_registration_otps
                    (id, email, first_name, last_name, phone, fin_kod, password_hash, otp_hash, attempts, created_at, expires_at)
                values (?, ?, 'Late', 'Admin', '+994501234567', ?, 'not-a-real-hash', ?, 0, now(), now() + interval '10 minutes')
                """, UUID.randomUUID(), email, finKod(), TokenHasher.sha256Hex(otp));

        api.post("/api/auth/admin/register/verify")
                .json(Json.object("email", email, "otp", otp))
                .send().expectError(403, "ADMIN_REGISTER_CLOSED");

        assertThat(jdbc.queryForObject("select count(*) from users where email = ?", Integer.class, email))
                .as("accounts created").isZero();
    }

    /** Seven uppercase letters or digits, as AdminRegisterStartRequest requires. */
    private static String finKod() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 7).toUpperCase(Locale.ROOT);
    }
}
