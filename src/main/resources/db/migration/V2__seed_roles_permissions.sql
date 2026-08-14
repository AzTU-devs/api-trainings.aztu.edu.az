-- =====================================================================
-- Seed system roles and core permissions.
-- Idempotent: every INSERT uses ON CONFLICT DO NOTHING.
-- =====================================================================

-- Roles --------------------------------------------------------------
INSERT INTO roles (id, code, name, description, is_system)
VALUES
    (gen_random_uuid(), 'USER',        'User',         'End-user / learner',            TRUE),
    (gen_random_uuid(), 'TUTOR',       'Tutor',        'Course author and instructor',  TRUE),
    (gen_random_uuid(), 'ADMIN',       'Admin',        'Platform administrator',        TRUE),
    (gen_random_uuid(), 'SUPER_ADMIN', 'Super Admin',  'Platform-wide owner / auditor', TRUE)
ON CONFLICT (code) DO NOTHING;

-- Permissions --------------------------------------------------------
INSERT INTO permissions (id, code, resource, action, description) VALUES
    -- user / profile
    (gen_random_uuid(), 'user:read',           'user',     'read',    'Read user profile'),
    (gen_random_uuid(), 'user:update_self',    'user',     'update',  'Update own profile'),
    (gen_random_uuid(), 'user:manage',         'user',     'manage',  'Admin user management'),
    -- tutor
    (gen_random_uuid(), 'tutor:apply',         'tutor',    'create',  'Apply to become a tutor'),
    (gen_random_uuid(), 'tutor:approve',       'tutor',    'approve', 'Approve / reject tutor applications'),
    (gen_random_uuid(), 'tutor:manage_self',   'tutor',    'update',  'Manage own tutor profile'),
    -- category / tag
    (gen_random_uuid(), 'category:read',       'category', 'read',    'Read categories'),
    (gen_random_uuid(), 'category:manage',     'category', 'manage',  'Create / update / delete categories'),
    (gen_random_uuid(), 'tag:manage',          'tag',      'manage',  'Create / update / delete tags'),
    -- course
    (gen_random_uuid(), 'course:read',         'course',   'read',    'Read courses'),
    (gen_random_uuid(), 'course:create',       'course',   'create',  'Create a course (tutor)'),
    (gen_random_uuid(), 'course:update_own',   'course',   'update',  'Update own courses (tutor)'),
    (gen_random_uuid(), 'course:publish',      'course',   'publish', 'Publish / unpublish a course (admin)'),
    (gen_random_uuid(), 'course:approve',      'course',   'approve', 'Approve / reject a course for publishing'),
    (gen_random_uuid(), 'course:manage',       'course',   'manage',  'Admin full course management'),
    -- room
    (gen_random_uuid(), 'room:read',           'room',     'read',    'Read rooms'),
    (gen_random_uuid(), 'room:manage',         'room',     'manage',  'Create / update / delete rooms (admin)'),
    (gen_random_uuid(), 'room:book',           'room',     'book',    'Request a room booking (tutor)'),
    (gen_random_uuid(), 'room:approve',        'room',     'approve', 'Approve / reject room bookings (admin)'),
    -- enrollment
    (gen_random_uuid(), 'enrollment:create',   'enrollment','create', 'Enroll in a course (user)'),
    (gen_random_uuid(), 'enrollment:read_own', 'enrollment','read',   'Read own enrollments'),
    (gen_random_uuid(), 'enrollment:manage',   'enrollment','manage', 'Admin enrollment management'),
    -- attendance
    (gen_random_uuid(), 'attendance:mark',     'attendance','update', 'Mark attendance (tutor)'),
    -- payment
    (gen_random_uuid(), 'payment:checkout',    'payment',  'create',  'Create checkout order (user)'),
    (gen_random_uuid(), 'payment:read_own',    'payment',  'read',    'Read own payments'),
    (gen_random_uuid(), 'payment:manage',      'payment',  'manage',  'Admin payment management'),
    -- review
    (gen_random_uuid(), 'review:create',       'review',   'create',  'Write a course review'),
    (gen_random_uuid(), 'review:moderate',     'review',   'moderate','Moderate reviews (admin)'),
    -- notification
    (gen_random_uuid(), 'notification:read_own','notification','read', 'Read own notifications'),
    (gen_random_uuid(), 'notification:manage', 'notification','manage','Admin notification / template management'),
    -- audit
    (gen_random_uuid(), 'audit:read',          'audit',    'read',    'Read audit logs (super admin)')
ON CONFLICT (code) DO NOTHING;

-- Role <-> permission wiring ------------------------------------------
WITH r AS (SELECT id, code FROM roles),
     p AS (SELECT id, code FROM permissions)
INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM r, p
WHERE
    -- USER
    (r.code = 'USER' AND p.code IN (
        'user:read','user:update_self',
        'category:read','course:read',
        'enrollment:create','enrollment:read_own',
        'payment:checkout','payment:read_own',
        'review:create',
        'notification:read_own'
    ))
    OR
    -- TUTOR (inherits user-ish basics + tutor capabilities)
    (r.code = 'TUTOR' AND p.code IN (
        'user:read','user:update_self',
        'category:read',
        'tutor:apply','tutor:manage_self',
        'course:read','course:create','course:update_own',
        'room:read','room:book',
        'attendance:mark',
        'enrollment:read_own',
        'review:create',
        'notification:read_own'
    ))
    OR
    -- ADMIN
    (r.code = 'ADMIN' AND p.code IN (
        'user:read','user:manage',
        'tutor:approve',
        'category:read','category:manage','tag:manage',
        'course:read','course:approve','course:publish','course:manage',
        'room:read','room:manage','room:approve',
        'enrollment:manage',
        'payment:manage',
        'review:moderate',
        'notification:manage'
    ))
    OR
    -- SUPER_ADMIN gets every permission
    (r.code = 'SUPER_ADMIN')
ON CONFLICT DO NOTHING;
