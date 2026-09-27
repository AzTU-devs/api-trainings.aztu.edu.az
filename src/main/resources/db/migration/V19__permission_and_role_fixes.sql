-- =====================================================================
-- Permission gaps, and the roles a withdrawn approval should have taken
-- away. Idempotent: every grant uses ON CONFLICT DO NOTHING. Grants reach
-- a signed-in user at their next token refresh.
-- =====================================================================

-- ADMIN could not read its own notifications. V2 granted notification:manage
-- (sending broadcasts) but not notification:read_own, which the whole
-- /api/portal/notifications controller requires, so the dashboard bell and
-- Notifications page answered 403 on every page for every ADMIN — while the
-- broadcasts addressed to admins did arrive over the WebSocket.
INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id
FROM roles r, permissions p
WHERE r.code = 'ADMIN' AND p.code = 'notification:read_own'
ON CONFLICT DO NOTHING;

-- POST /api/portal/tutor/apply requires tutor:apply, which V2 granted only to
-- TUTOR — the one role that already has a profile and so can never apply.
-- A participant who wants to teach applies from their own account.
INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id
FROM roles r, permissions p
WHERE r.code = 'USER' AND p.code = 'tutor:apply'
ON CONFLICT DO NOTHING;

-- Rejecting an already-approved expert used to leave them the TUTOR role,
-- and with it the right to edit and submit their courses. The decision now
-- revokes the role (TutorService.decide); this applies the same outcome to
-- the experts rejected before that. Re-approving grants it back.
-- token_version is bumped so their current access tokens stop working now.
UPDATE users u
   SET token_version = u.token_version + 1
 WHERE EXISTS (SELECT 1 FROM tutor_profiles t
                WHERE t.user_id = u.id AND t.approval_status = 'REJECTED' AND t.deleted_at IS NULL)
   AND EXISTS (SELECT 1 FROM user_roles ur JOIN roles r ON r.id = ur.role_id
                WHERE ur.user_id = u.id AND r.code = 'TUTOR');

DELETE FROM user_roles ur
 USING roles r, tutor_profiles t
 WHERE r.id = ur.role_id
   AND r.code = 'TUTOR'
   AND t.user_id = ur.user_id
   AND t.approval_status = 'REJECTED'
   AND t.deleted_at IS NULL;
