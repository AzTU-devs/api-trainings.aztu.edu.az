-- =====================================================================
-- An ADMIN runs the platform day to day, so authoring a training cannot be a
-- super-admin errand: the dashboard's course area was unreachable for an admin
-- because POST /api/admin/courses demands course:create_any, which V10 granted
-- to SUPER_ADMIN alone.
--
-- This one grant is the whole gap. From V2 an ADMIN already holds course:manage
-- (edit any course), course:publish (publish and unpublish) and
-- enrollment:manage (add and remove participants); only creating a course on
-- behalf of the university was withheld. Nothing is added to SUPER_ADMIN, which
-- already has every permission, and no existing grant is touched.
-- =====================================================================

INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id
FROM roles r, permissions p
WHERE r.code = 'ADMIN' AND p.code = 'course:create_any'
ON CONFLICT DO NOTHING;
