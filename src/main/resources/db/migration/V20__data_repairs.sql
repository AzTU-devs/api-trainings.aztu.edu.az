-- =====================================================================
-- Repairs to data that earlier versions of the API let go wrong. Each
-- statement only touches rows that are actually wrong, so re-running the
-- logic on a clean database changes nothing.
-- =====================================================================

-- 1. Expert profile links and years from sign-up. Sign-up did not validate
--    them (the profile edit always did), so a javascript: or data: link, or
--    one carrying credentials ("https://linkedin.com@evil.example"), could be
--    stored and then rendered as an href on the public expert page; and a
--    500-year career blocked every later profile save. A bare "www." host is
--    given the https:// it was obviously meant to have; anything else that is
--    not an http(s) address with a plain host is cleared.
UPDATE tutor_profiles SET website_url = 'https://' || website_url
 WHERE website_url ~* '^www\.[^\s/@]+';
UPDATE tutor_profiles SET linkedin_url = 'https://' || linkedin_url
 WHERE linkedin_url ~* '^www\.[^\s/@]+';
UPDATE tutor_profiles SET website_url = NULL
 WHERE website_url IS NOT NULL
   AND website_url !~* '^https?://[^/@\s?#]+([/?#]\S*)?$';
UPDATE tutor_profiles SET linkedin_url = NULL
 WHERE linkedin_url IS NOT NULL
   AND linkedin_url !~* '^https?://[^/@\s?#]+([/?#]\S*)?$';
UPDATE tutor_profiles SET years_experience = NULL
 WHERE years_experience < 0 OR years_experience > 80;

-- The same for sign-ups still waiting for their code.
UPDATE tutor_registration_otps SET website_url = NULL
 WHERE website_url IS NOT NULL
   AND website_url !~* '^https?://[^/@\s?#]+([/?#]\S*)?$';
UPDATE tutor_registration_otps SET linkedin_url = NULL
 WHERE linkedin_url IS NOT NULL
   AND linkedin_url !~* '^https?://[^/@\s?#]+([/?#]\S*)?$';
UPDATE tutor_registration_otps SET years_experience = NULL
 WHERE years_experience < 0 OR years_experience > 80;

-- 2. Participants an administrator removed whose own progress then undid the
--    removal. Removing a participant cancels the enrolment, and a lesson
--    completed afterwards could still take it to 100% and set it COMPLETED —
--    back on the roster. Such a row's latest enrolment audit entry is the
--    removal (DELETE); a re-grant would have recorded a CREATE after it, and
--    progress writes no audit entry at all.
UPDATE enrollments e
   SET status = 'CANCELLED'
 WHERE e.status = 'COMPLETED'
   AND e.deleted_at IS NULL
   AND (SELECT a.action
          FROM audit_logs a
         WHERE a.entity_type = 'ENROLLMENT' AND a.entity_id = e.id
         ORDER BY a.occurred_at DESC
         LIMIT 1) = 'DELETE';

-- 3. The places counted on each course: ACTIVE and COMPLETED enrolments, the
--    ones that hold a place. The course count drifted with the progress bug
--    above, and the in-person seat count was never kept at all, so it read 0.
UPDATE courses c
   SET enrolled_count = x.places
  FROM (SELECT c2.id,
               (SELECT count(*) FROM enrollments e
                 WHERE e.course_id = c2.id AND e.deleted_at IS NULL
                   AND e.status IN ('ACTIVE', 'COMPLETED')) AS places
          FROM courses c2) x
 WHERE x.id = c.id
   AND c.enrolled_count <> x.places;

UPDATE offline_course_details o
   SET enrolled_count = x.places
  FROM (SELECT o2.course_id,
               (SELECT count(*) FROM enrollments e
                 WHERE e.course_id = o2.course_id AND e.deleted_at IS NULL
                   AND e.status IN ('ACTIVE', 'COMPLETED')) AS places
          FROM offline_course_details o2) x
 WHERE x.course_id = o.course_id
   AND o.enrolled_count <> x.places;
