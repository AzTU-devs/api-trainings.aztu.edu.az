-- =====================================================================
-- Two pieces of per-account state that the sign-in rules need.
--
-- locked_until: failed-login lockout becomes temporary. Five wrong
-- passwords used to set status = LOCKED with no expiry, and only a
-- SUPER_ADMIN (or a mailed reset, with mail switched off) could undo it,
-- so anyone who knew an address could lock that account for good,
-- every SUPER_ADMIN included. A lock is now a moment in time that passes
-- by itself; status is left to administrators.
--
-- token_version: carried in every access token as the "ver" claim and
-- bumped whenever an administrator changes an account's roles, status or
-- password. The API compares the two on each request, so a demoted,
-- disabled or deleted account stops working at once instead of keeping
-- its powers for the rest of a 15-minute access token.
--
-- Purely additive on a populated table: both columns have defaults, and
-- ADD COLUMN with a constant default does not rewrite the table.
-- =====================================================================

ALTER TABLE users
    ADD COLUMN IF NOT EXISTS locked_until  TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS token_version BIGINT NOT NULL DEFAULT 0;

-- Accounts locked under the old rule were locked by failed guesses, not by
-- an administrator, so they go back to ACTIVE. Nothing sets LOCKED any more;
-- disabling an account is SUSPENDED, which is left alone.
UPDATE users
   SET status = 'ACTIVE',
       failed_logins = 0
 WHERE status = 'LOCKED';
