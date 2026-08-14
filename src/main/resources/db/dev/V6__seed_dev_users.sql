-- =====================================================================
-- Seed one login-ready account per role, for development / QA testing.
-- Idempotent: re-running does nothing (ON CONFLICT DO NOTHING).
--
-- All four accounts share the password:  Password123!
-- (bcrypt cost 12 hashes below — Spring Security verifies the $2y$ prefix.)
--
--   USER         user@eduplatform.local
--   TUTOR        tutor@eduplatform.local
--   ADMIN        admin@eduplatform.local
--   SUPER_ADMIN  superadmin@eduplatform.local
--
-- SECURITY: these are test credentials. Rotate or delete them before any
-- real production exposure.
-- =====================================================================

-- Accounts -----------------------------------------------------------
INSERT INTO users (id, email, phone, password_hash, first_name, last_name,
                   status, email_verified_at, locale, fin_kod)
VALUES
    (gen_random_uuid(), 'user@eduplatform.local',       NULL,
     '$2y$12$4VG1fqEOfc0Tn0SuvZ1r.uddBlxtiTuTZH0azq72GlMeLUVNJDDKe',
     'Test', 'User',        'ACTIVE', now(), 'en', NULL),

    (gen_random_uuid(), 'tutor@eduplatform.local',      NULL,
     '$2y$12$9e1R3WbBH8C4HOanX0UfPeFRazNbzZiqRn9MU5kKtaRd7r7xgTi.6',
     'Test', 'Tutor',       'ACTIVE', now(), 'en', NULL),

    (gen_random_uuid(), 'admin@eduplatform.local',      NULL,
     '$2y$12$PoPXY3Ljte9tKgYw2Q.Zl.yrDvix24lCN1pvY5qkVFW8vdAskaMUu',
     'Test', 'Admin',       'ACTIVE', now(), 'en', 'ADMIN01'),

    (gen_random_uuid(), 'superadmin@eduplatform.local', NULL,
     '$2y$12$NN61e7XJe079xBMZkJTP3e.ftHSegfEEkbVoj1tU7H9758nT11yKu',
     'Test', 'SuperAdmin',  'ACTIVE', now(), 'en', 'SUPER01')
ON CONFLICT (email) DO NOTHING;

-- Role assignment ----------------------------------------------------
WITH seed (email, role_code) AS (
    VALUES
        ('user@eduplatform.local',       'USER'),
        ('tutor@eduplatform.local',      'TUTOR'),
        ('admin@eduplatform.local',      'ADMIN'),
        ('superadmin@eduplatform.local', 'SUPER_ADMIN')
)
INSERT INTO user_roles (user_id, role_id, granted_at)
SELECT u.id, r.id, now()
FROM seed s
JOIN users u ON u.email = s.email
JOIN roles r ON r.code = s.role_code
ON CONFLICT DO NOTHING;
