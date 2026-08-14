-- =====================================================================
-- Observability + admin features added after the initial schema.
--   * api_request_logs  — persisted HTTP API access log (super-admin view)
--   * blocked_ips       — IP blocklist enforced by the access interceptor
--   * new permissions   — analytics / api-logs / security / system
-- Idempotent where practical.
-- =====================================================================

-- API request log -----------------------------------------------------
CREATE TABLE IF NOT EXISTS api_request_logs (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    method          VARCHAR(10)  NOT NULL,
    path            VARCHAR(512) NOT NULL,
    status          INT          NOT NULL,
    latency_ms      BIGINT       NOT NULL,
    ip_address      VARCHAR(45),
    user_agent      VARCHAR(255),
    actor_id        UUID,
    actor_email     VARCHAR(255),
    request_id      UUID,
    error_message   VARCHAR(500),
    response_bytes  BIGINT,
    occurred_at     TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_api_logs_occurred_at ON api_request_logs(occurred_at DESC);
CREATE INDEX IF NOT EXISTS idx_api_logs_status      ON api_request_logs(status);
CREATE INDEX IF NOT EXISTS idx_api_logs_actor       ON api_request_logs(actor_id);

-- IP blocklist --------------------------------------------------------
CREATE TABLE IF NOT EXISTS blocked_ips (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    ip_address  VARCHAR(45) NOT NULL UNIQUE,
    reason      VARCHAR(255),
    created_by  UUID,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- New permissions -----------------------------------------------------
INSERT INTO permissions (id, code, resource, action, description) VALUES
    (gen_random_uuid(), 'analytics:read',  'analytics', 'read',   'Read analytics dashboards (admin)'),
    (gen_random_uuid(), 'apilog:read',     'apilog',    'read',   'Read API request logs (super admin)'),
    (gen_random_uuid(), 'security:manage', 'security',  'manage', 'View and manage security (super admin)'),
    (gen_random_uuid(), 'system:read',     'system',    'read',   'Read system health (super admin)')
ON CONFLICT (code) DO NOTHING;

-- Role wiring: analytics for ADMIN + SUPER_ADMIN; the rest for SUPER_ADMIN.
WITH r AS (SELECT id, code FROM roles),
     p AS (SELECT id, code FROM permissions)
INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM r, p
WHERE (r.code = 'ADMIN' AND p.code = 'analytics:read')
   OR (r.code = 'SUPER_ADMIN' AND p.code IN ('analytics:read', 'apilog:read', 'security:manage', 'system:read'))
ON CONFLICT DO NOTHING;
