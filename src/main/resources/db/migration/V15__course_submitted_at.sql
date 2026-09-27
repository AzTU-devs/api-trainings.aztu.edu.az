-- =====================================================================
-- When a course was last submitted for review. The moderation queue has a
-- "Submitted" column that always read "—", because the submission was a
-- status change with no time recorded anywhere.
--
-- Additive and nullable. Courses already waiting in review get their last
-- change as the best available estimate, so the queue can still sort them.
-- =====================================================================

ALTER TABLE courses ADD COLUMN IF NOT EXISTS submitted_at TIMESTAMPTZ;

UPDATE courses
   SET submitted_at = updated_at
 WHERE status = 'IN_REVIEW'
   AND submitted_at IS NULL;
