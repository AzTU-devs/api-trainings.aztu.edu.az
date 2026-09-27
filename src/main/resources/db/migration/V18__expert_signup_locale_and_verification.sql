-- =====================================================================
-- Expert sign-up keeps the language the applicant chose, and an account it
-- creates counts as email-verified: entering the code that was mailed to
-- the address is exactly what verifying the address means. The account was
-- created in English whatever the applicant chose, and the dashboard showed
-- every expert who signed up this way as "Unverified".
-- =====================================================================

ALTER TABLE tutor_registration_otps ADD COLUMN IF NOT EXISTS locale VARCHAR(8);

-- Backfill the accounts already created by the OTP flow. A code row is
-- marked consumed when a successful verify creates the account, and also
-- when a newer code replaces it or too many attempts use it up. Only the
-- first can happen after the account exists, so a consumption at or after
-- the account's creation identifies it. Rows the hourly cleanup has already
-- purged cannot be matched; those accounts can still verify by link.
UPDATE users u
   SET email_verified_at = o.consumed_at
  FROM tutor_registration_otps o
 WHERE o.email = u.email
   AND o.consumed_at IS NOT NULL
   AND o.consumed_at >= u.created_at
   AND u.email_verified_at IS NULL
   AND u.deleted_at IS NULL;
