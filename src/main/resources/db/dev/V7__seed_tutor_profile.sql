-- =====================================================================
-- Give the seeded tutor (tutor@eduplatform.local) an APPROVED tutor
-- profile. Having the TUTOR role grants the `course:create` authority,
-- but CourseService.createByTutor additionally requires a TutorProfile
-- row whose approval_status = 'APPROVED' — otherwise it rejects with
-- NOT_A_TUTOR / TUTOR_NOT_APPROVED. The normal tutor-signup flow creates
-- this profile; the dev seed bypassed it, so we add it here.
--
-- Idempotent: ON CONFLICT (user_id) DO NOTHING. No-op if the tutor account
-- doesn't exist (the INSERT ... SELECT simply matches no rows).
-- =====================================================================

INSERT INTO tutor_profiles (id, user_id, headline, bio,
                            approval_status, approved_at,
                            rating_avg, rating_count)
SELECT gen_random_uuid(), u.id,
       'Test Tutor',
       'Seeded tutor profile for development / QA.',
       'APPROVED', now(),
       0.00, 0
FROM users u
WHERE u.email = 'tutor@eduplatform.local'
ON CONFLICT (user_id) DO NOTHING;
