-- =====================================================================
-- Tutor self-registration with OTP. Public (no auth):
--   1. /start  → details stashed here, OTP emailed
--   2. /verify → OTP checked, user (USER role) + PENDING tutor profile
--                + approval request created; admin then approves.
-- =====================================================================

CREATE TABLE tutor_registration_otps (
    id               UUID PRIMARY KEY,
    email            CITEXT NOT NULL,
    first_name       VARCHAR(80) NOT NULL,
    last_name        VARCHAR(80) NOT NULL,
    phone            VARCHAR(32),
    password_hash    VARCHAR(255) NOT NULL,
    headline         VARCHAR(160),
    bio              TEXT,
    years_experience SMALLINT,
    website_url      VARCHAR(255),
    linkedin_url     VARCHAR(255),
    category_ids     JSONB NOT NULL,                  -- list of category UUID strings
    otp_hash         VARCHAR(64) NOT NULL,            -- SHA-256 hex of the 6-digit OTP
    attempts         SMALLINT NOT NULL DEFAULT 0,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at       TIMESTAMPTZ NOT NULL,
    consumed_at      TIMESTAMPTZ,
    CONSTRAINT chk_tutor_otp_attempts CHECK (attempts >= 0)
);
CREATE INDEX idx_tutor_otp_email_active
    ON tutor_registration_otps (email) WHERE consumed_at IS NULL;
CREATE INDEX idx_tutor_otp_expires
    ON tutor_registration_otps (expires_at);
