-- =====================================================================
-- One-time tokens for password reset and email verification.
-- =====================================================================
CREATE TABLE IF NOT EXISTS auth_action_tokens (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id     UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    purpose     VARCHAR(40) NOT NULL,          -- PASSWORD_RESET | EMAIL_VERIFY
    token_hash  VARCHAR(64) NOT NULL,
    expires_at  TIMESTAMPTZ NOT NULL,
    consumed_at TIMESTAMPTZ,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_auth_action_tokens_hash ON auth_action_tokens(token_hash);
CREATE INDEX IF NOT EXISTS idx_auth_action_tokens_user ON auth_action_tokens(user_id);
