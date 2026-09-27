-- =====================================================================
-- What deleting an account used to leave behind, the values earlier rules
-- let in, and the per-address sign-in throttle.
--
-- Like V20, every UPDATE touches only rows that are actually wrong, so on a
-- clean database this file changes nothing but the schema additions at the
-- end, and running its logic twice changes nothing the second time.
-- =====================================================================

-- 1. Experts whose account was deleted before deleting an account retired
--    its expert profile. The profile stayed live and pointed at a user no
--    read can load, so the Tutors page (every APPROVED expert) and the admin
--    view of that expert's courses failed for everyone. The API now retires
--    the profile with the account when nothing names it, and refuses the
--    deletion when something does; this does the same retirement for the
--    accounts deleted before that. A profile still named on a live course
--    or a room booking is left in place, because the course and the booking
--    need it: the reads now tolerate it (the course keeps the expert's name
--    and can be reassigned; the expert lists and the public expert page
--    leave it out), and once nothing names it any more it is inert.
UPDATE tutor_profiles t
   SET deleted_at = u.deleted_at
  FROM users u
 WHERE u.id = t.user_id
   AND u.deleted_at IS NOT NULL
   AND t.deleted_at IS NULL
   AND NOT EXISTS (SELECT 1 FROM courses c
                    WHERE c.deleted_at IS NULL
                      AND (c.tutor_id = t.id
                           OR EXISTS (SELECT 1 FROM course_tutors ct
                                       WHERE ct.course_id = c.id AND ct.tutor_id = t.id)))
   AND NOT EXISTS (SELECT 1 FROM room_bookings b
                    WHERE b.tutor_id = t.id AND b.deleted_at IS NULL);

-- 2. Sessions of deleted accounts. Deleting did not revoke the refresh
--    tokens; refreshing is refused now, but the rows still read as live.
UPDATE refresh_tokens rt
   SET revoked_at = now(),
       revoke_reason = 'ADMIN'
  FROM users u
 WHERE u.id = rt.user_id
   AND u.deleted_at IS NOT NULL
   AND rt.revoked_at IS NULL;

-- 3. Places held by deleted accounts. Deleting a participant cancelled
--    nothing, so an in-person seat stayed taken for good: the roster (which
--    lists live accounts) showed nobody, while the course said it was full.
--    The API now cancels them with the account; these are the ones deleted
--    before. PENDING_PAYMENT goes too, so no checkout can complete for an
--    account that no longer exists.
UPDATE enrollments e
   SET status = 'CANCELLED'
  FROM users u
 WHERE u.id = e.user_id
   AND u.deleted_at IS NOT NULL
   AND e.deleted_at IS NULL
   AND e.status IN ('ACTIVE', 'COMPLETED', 'PENDING_PAYMENT');

-- 4. Reviews by deleted accounts. The public and moderation lists already
--    left them out (they join the author), but the course rating still
--    counted them, and moderation could not act on them. They go with the
--    account, as they now do when an account is deleted.
UPDATE course_reviews r
   SET deleted_at = u.deleted_at
  FROM users u
 WHERE u.id = r.user_id
   AND u.deleted_at IS NOT NULL
   AND r.deleted_at IS NULL;

-- 5. Recount what 3 and 4 changed, with the rules the API applies: places are
--    ACTIVE and COMPLETED enrolments of live accounts; the rating is the mean
--    of the visible reviews of live accounts, to two places, as the API
--    rounds it (half up; ratings are positive, so round() agrees).
UPDATE courses c
   SET enrolled_count = x.places
  FROM (SELECT c2.id,
               (SELECT count(*) FROM enrollments e
                  JOIN users u ON u.id = e.user_id AND u.deleted_at IS NULL
                 WHERE e.course_id = c2.id AND e.deleted_at IS NULL
                   AND e.status IN ('ACTIVE', 'COMPLETED')) AS places
          FROM courses c2) x
 WHERE x.id = c.id
   AND c.enrolled_count <> x.places;

UPDATE offline_course_details o
   SET enrolled_count = x.places
  FROM (SELECT o2.course_id,
               (SELECT count(*) FROM enrollments e
                  JOIN users u ON u.id = e.user_id AND u.deleted_at IS NULL
                 WHERE e.course_id = o2.course_id AND e.deleted_at IS NULL
                   AND e.status IN ('ACTIVE', 'COMPLETED')) AS places
          FROM offline_course_details o2) x
 WHERE x.course_id = o.course_id
   AND o.enrolled_count <> x.places;

UPDATE courses c
   SET rating_avg = x.avg_rating,
       rating_count = x.reviews
  FROM (SELECT c2.id,
               COALESCE((SELECT round(avg(r.rating), 2) FROM course_reviews r
                           JOIN users u ON u.id = r.user_id AND u.deleted_at IS NULL
                          WHERE r.course_id = c2.id AND r.deleted_at IS NULL AND r.is_visible), 0) AS avg_rating,
               (SELECT count(*) FROM course_reviews r
                  JOIN users u ON u.id = r.user_id AND u.deleted_at IS NULL
                 WHERE r.course_id = c2.id AND r.deleted_at IS NULL AND r.is_visible) AS reviews
          FROM courses c2) x
 WHERE x.id = c.id
   AND (c.rating_avg <> x.avg_rating OR c.rating_count <> x.reviews);

-- 6. In-person hours. Nothing bounded them, so negative hours were stored and
--    shown on the public course page, and values past the columns' precision
--    failed as a generic "cannot be stored". The API now takes 0-168 hours a
--    week and 0-99999.9 in total; values outside that carry no meaning, so
--    they are cleared rather than guessed at, and the table holds the rule
--    from here on.
UPDATE offline_course_details SET weekly_hours = NULL
 WHERE weekly_hours < 0 OR weekly_hours > 168;
UPDATE offline_course_details SET total_hours = NULL
 WHERE total_hours < 0;

ALTER TABLE offline_course_details
    DROP CONSTRAINT IF EXISTS chk_offline_hours;
ALTER TABLE offline_course_details
    ADD CONSTRAINT chk_offline_hours CHECK (
        (weekly_hours IS NULL OR weekly_hours BETWEEN 0 AND 168)
        AND (total_hours IS NULL OR total_hours >= 0));

-- 7. Lesson links. Only their length was checked, so a javascript: or data:
--    link could be stored, and for a preview lesson it was served to anonymous
--    visitors. The API now takes http(s) addresses only, the rule the expert
--    profile links follow (V20). Surrounding spaces are dropped, a bare host
--    ("meet.google.com/abc") gets the https:// it was meant to have, and
--    anything that is still not an http(s) address with a plain host is
--    cleared.
UPDATE lessons SET video_url = NULLIF(btrim(video_url), '')
 WHERE video_url IS NOT NULL AND (video_url <> btrim(video_url) OR video_url = '');
UPDATE lessons SET video_url = 'https://' || video_url
 WHERE video_url ~* '^[a-z0-9-]+(\.[a-z0-9-]+)+([/?#]\S*)?$';
UPDATE lessons SET video_url = NULL
 WHERE video_url IS NOT NULL
   AND video_url !~* '^https?://[^/@\s?#]+([/?#]\S*)?$';

-- 8. Phone numbers with no digit in them ("-------"), which the old pattern
--    accepted. The new one needs 7-15 digits; a value without a single digit
--    is no number at all. Others are left as they are.
UPDATE users SET phone = NULL
 WHERE phone IS NOT NULL AND phone !~ '[0-9]';

-- 9. The sign-in throttle, per address typed and per client address. The
--    lockout used to be kept on the account alone and checked before the
--    password, so anyone who knew an address could keep its owner out
--    indefinitely with five wrong passwords every fifteen minutes. A lock now
--    shuts out only the client address the failures came from; the owner
--    signing in from anywhere else is not affected. Keyed on a hash of the
--    typed address, known or not, so the lock says nothing about whether an
--    account exists and the table holds no list of addresses people tried.
--    users.failed_logins / locked_until stay as the dashboard's record that
--    somebody is failing to sign in to that account.
CREATE TABLE IF NOT EXISTS login_throttles (
    email_hash     CHAR(64)    NOT NULL,
    client_ip      VARCHAR(64) NOT NULL,
    failures       SMALLINT    NOT NULL DEFAULT 0,
    locked_until   TIMESTAMPTZ,
    last_failed_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (email_hash, client_ip)
);
CREATE INDEX IF NOT EXISTS idx_login_throttles_last_failed ON login_throttles (last_failed_at);
