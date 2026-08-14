-- =====================================================================
-- Admin self-registration with OTP. v1 leaves the endpoint open
-- (no auth) so the first admin can bootstrap; lock down later by
-- requiring an existing SUPER_ADMIN invite token.
-- =====================================================================

-- Admin profiles need a fin_kod (Azerbaijani 7-char tax-id-equivalent).
-- Nullable on the table because end-users don't have one.
ALTER TABLE users ADD COLUMN IF NOT EXISTS fin_kod VARCHAR(20);
CREATE UNIQUE INDEX IF NOT EXISTS uq_users_fin_kod
    ON users (fin_kod) WHERE fin_kod IS NOT NULL AND deleted_at IS NULL;

-- Two-step admin signup: details + hashed password stashed here, the user
-- then submits the OTP that was emailed to them to actually create the account.
CREATE TABLE admin_registration_otps (
    id            UUID PRIMARY KEY,
    email         CITEXT NOT NULL,
    first_name    VARCHAR(80) NOT NULL,
    last_name     VARCHAR(80) NOT NULL,
    phone         VARCHAR(32) NOT NULL,
    fin_kod       VARCHAR(20) NOT NULL,
    password_hash VARCHAR(255) NOT NULL,
    otp_hash      VARCHAR(64) NOT NULL,            -- SHA-256 hex of the 6-digit OTP
    attempts      SMALLINT NOT NULL DEFAULT 0,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at    TIMESTAMPTZ NOT NULL,
    consumed_at   TIMESTAMPTZ,
    CONSTRAINT chk_admin_otp_attempts CHECK (attempts >= 0)
);
CREATE INDEX idx_admin_otp_email_active
    ON admin_registration_otps (email) WHERE consumed_at IS NULL;
CREATE INDEX idx_admin_otp_expires
    ON admin_registration_otps (expires_at);
