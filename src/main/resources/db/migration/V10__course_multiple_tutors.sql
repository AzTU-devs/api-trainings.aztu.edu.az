-- =====================================================================
-- A training may be taught by more than one tutor.
--
-- Courses belong to Azerbaijan Technical University, not to an individual.
-- What varies per course is which tutor is authorised to edit it, and that is
-- exactly what courses.tutor_id already expresses — so the column is kept and
-- reinterpreted rather than replaced. Every existing ownership check
-- (course.getTutor().getUser().getId().equals(userId)), the tutor "my courses"
-- queries and the enrolment analytics therefore keep working unchanged.
--
--   courses.tutor_id  -> the ONE tutor authorised to edit this course
--   course_tutors     -> the full teaching roster (includes the authorised one)
--
-- Deliberately no is_lead column: it would duplicate courses.tutor_id and the
-- two could drift apart. The authorised editor is always courses.tutor_id.
-- =====================================================================

CREATE TABLE course_tutors (
    course_id   UUID NOT NULL REFERENCES courses(id)        ON DELETE CASCADE,
    tutor_id    UUID NOT NULL REFERENCES tutor_profiles(id) ON DELETE RESTRICT,
    assigned_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    assigned_by UUID REFERENCES users(id) ON DELETE SET NULL,
    PRIMARY KEY (course_id, tutor_id)
);

-- Listing "which courses does this tutor teach" is the hot path for the tutor
-- dashboard; the PK already covers the course_id direction.
CREATE INDEX idx_course_tutors_tutor ON course_tutors(tutor_id);

COMMENT ON TABLE course_tutors IS
    'Full teaching roster for a course. The tutor authorised to edit the course is courses.tutor_id, which is always also present here.';

COMMENT ON COLUMN courses.tutor_id IS
    'The tutor authorised to edit this course. The course itself belongs to the university; see course_tutors for the full roster.';

-- Backfill: every existing course has exactly one tutor, who becomes both the
-- authorised editor (already true) and the first roster entry.
INSERT INTO course_tutors (course_id, tutor_id)
SELECT c.id, c.tutor_id
FROM courses c
ON CONFLICT DO NOTHING;

-- =====================================================================
-- Creating a course on behalf of the university is distinct from a tutor
-- creating their own (course:create) and from moderating one (course:manage,
-- which ADMIN also holds). It gets its own permission, granted to SUPER_ADMIN
-- only. Note V2's "SUPER_ADMIN gets every permission" was a one-time insert, so
-- permissions added later must be granted explicitly.
-- =====================================================================

INSERT INTO permissions (id, code, resource, action, description) VALUES
    (gen_random_uuid(), 'course:create_any', 'course', 'create',
     'Create a course on behalf of the university and assign its tutors')
ON CONFLICT (code) DO NOTHING;

INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id
FROM roles r, permissions p
WHERE r.code = 'SUPER_ADMIN' AND p.code = 'course:create_any'
ON CONFLICT DO NOTHING;
