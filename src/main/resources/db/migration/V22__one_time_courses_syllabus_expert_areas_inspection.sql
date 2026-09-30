-- =====================================================================
-- One change set across the API, the dashboard and the public site:
--   1. ONE_TIME courses: an in-person training held once, on a single
--      date between a start and an end time.
--   2. Syllabus items: the syllabus as an ordered list of titled entries.
--   3. A super admin's inspection of any account (user:inspect).
--   4. Areas of expertise an expert types in themselves.
--
-- Purely additive on populated tables: every new column is nullable or has
-- a constant default, which Postgres adds without rewriting the table; the
-- widened course-type CHECK accepts every row the old one did; and the new
-- CHECKs hold for every existing row, whose new columns are NULL or '[]'.
-- Safe to run twice: each statement is guarded or drops what it re-adds.
-- =====================================================================

-- 1. ONE_TIME courses -------------------------------------------------
-- A one-time training keeps its schedule in offline_course_details, as an
-- OFFLINE one does: start_date = end_date, plus the day's start and end
-- time, which an OFFLINE course may now record as well. Seats, room
-- bookings, sessions and attendance all hang off that row, so they work for
-- it unchanged. course_type is VARCHAR(10), which 'ONE_TIME' fits.
ALTER TABLE courses DROP CONSTRAINT IF EXISTS chk_course_type;
ALTER TABLE courses
    ADD CONSTRAINT chk_course_type CHECK (course_type IN ('ONLINE', 'OFFLINE', 'ONE_TIME'));

ALTER TABLE offline_course_details
    ADD COLUMN IF NOT EXISTS start_time TIME,
    ADD COLUMN IF NOT EXISTS end_time   TIME;

-- When both times are set, the end comes after the start. Whether a course
-- must have them at all (a ONE_TIME one must) depends on courses.course_type,
-- which a CHECK on this table cannot see; the API holds that rule, together
-- with the one-day rule and the total hours it derives from the times.
ALTER TABLE offline_course_details DROP CONSTRAINT IF EXISTS chk_offline_times;
ALTER TABLE offline_course_details
    ADD CONSTRAINT chk_offline_times
        CHECK (start_time IS NULL OR end_time IS NULL OR end_time > start_time);

-- 2. Syllabus items ---------------------------------------------------
-- [{"title": "...", "description": "<p>...</p>"}, ...] in display order.
-- JSONB on the course row rather than a child table: the items are only
-- ever read with the course and replaced as a whole, never queried or
-- referenced one by one. The free-text syllabus column stays for the
-- courses written before; readers fall back to it while this is empty.
ALTER TABLE courses
    ADD COLUMN IF NOT EXISTS syllabus_items JSONB NOT NULL DEFAULT '[]'::jsonb;
ALTER TABLE courses DROP CONSTRAINT IF EXISTS chk_course_syllabus_items;
ALTER TABLE courses
    ADD CONSTRAINT chk_course_syllabus_items CHECK (jsonb_typeof(syllabus_items) = 'array');

-- 3. Inspecting an account --------------------------------------------
-- GET /api/super/users/{id}/profile shows everything about an account:
-- its orders, sessions, security events and audit trail. That is more than
-- managing accounts (user:manage, which ADMIN holds), so it gets its own
-- permission, granted to SUPER_ADMIN only. V2's "SUPER_ADMIN gets every
-- permission" was a one-time cross join over the permissions that existed
-- then, so one created now reaches no role unless granted by name. A
-- signed-in super admin gets it with their next token refresh.
INSERT INTO permissions (id, code, resource, action, description) VALUES
    (gen_random_uuid(), 'user:inspect', 'user', 'read',
     'Read the full profile of any account, deleted ones included (super admin)')
ON CONFLICT (code) DO NOTHING;

INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id
FROM roles r, permissions p
WHERE r.code = 'SUPER_ADMIN' AND p.code = 'user:inspect'
ON CONFLICT DO NOTHING;

-- 4. An expert's own areas of expertise -------------------------------
-- Free-text labels next to the catalogue categories an expert picks
-- (tutor_expertises); they never become categories. The pending sign-up
-- row carries them through OTP verification to the profile it creates.
ALTER TABLE tutor_profiles
    ADD COLUMN IF NOT EXISTS custom_expertise JSONB NOT NULL DEFAULT '[]'::jsonb;
ALTER TABLE tutor_profiles DROP CONSTRAINT IF EXISTS chk_tutor_custom_expertise;
ALTER TABLE tutor_profiles
    ADD CONSTRAINT chk_tutor_custom_expertise CHECK (jsonb_typeof(custom_expertise) = 'array');

ALTER TABLE tutor_registration_otps
    ADD COLUMN IF NOT EXISTS custom_expertise JSONB NOT NULL DEFAULT '[]'::jsonb;
