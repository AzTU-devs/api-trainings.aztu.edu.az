-- =====================================================================
-- The super admin's training calendar: GET /api/admin/calendar shows every
-- in-person training, session and room booking across the platform, with
-- tutor names and seat counts. That overview is the super admin's alone,
-- so it gets its own permission rather than riding on course:approve or
-- room:approve, which ADMIN holds.
--
-- Same pattern as V22's user:inspect: V2's "SUPER_ADMIN gets every
-- permission" was a one-time cross join, so a permission created now
-- reaches no role unless granted by name. A signed-in super admin gets it
-- with their next token refresh. Safe to run twice.
-- =====================================================================

INSERT INTO permissions (id, code, resource, action, description) VALUES
    (gen_random_uuid(), 'calendar:view', 'calendar', 'read',
     'See the calendar of every training, session and room booking (super admin)')
ON CONFLICT (code) DO NOTHING;

INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id
FROM roles r, permissions p
WHERE r.code = 'SUPER_ADMIN' AND p.code = 'calendar:view'
ON CONFLICT DO NOTHING;
