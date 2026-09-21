-- =====================================================================
-- Expert profiles get the detail a university profile page is expected to
-- carry: a portrait, academic title and department, education and
-- certifications, spoken languages, and the research identities (Google
-- Scholar, ResearchGate, ORCID, GitHub) people look an academic up by.
--
-- Purely additive: every column is nullable, nothing is backfilled, and every
-- existing field keeps its meaning. Education and certifications are free
-- text, one entry per line, rather than child tables — they are only ever
-- shown as a list and never queried by, so a table would buy nothing.
-- =====================================================================

ALTER TABLE tutor_profiles
    -- SET NULL, not CASCADE: losing the picture must never take the profile
    -- with it.
    ADD COLUMN avatar_media_id    UUID REFERENCES media_files(id) ON DELETE SET NULL,
    ADD COLUMN academic_title     VARCHAR(120),
    ADD COLUMN department         VARCHAR(160),
    ADD COLUMN education          TEXT,
    ADD COLUMN certifications     TEXT,
    ADD COLUMN languages          VARCHAR(255),
    ADD COLUMN google_scholar_url VARCHAR(255),
    ADD COLUMN research_gate_url  VARCHAR(255),
    ADD COLUMN orcid              VARCHAR(255),
    ADD COLUMN github_url         VARCHAR(255);

-- The anonymous media endpoint asks "is this the avatar of an approved
-- expert?" for every id that is neither public nor a course asset, and the
-- foreign key's SET NULL has to find referencing rows when a file is deleted.
-- Most profiles have no avatar, so only the rows that do are indexed.
CREATE INDEX idx_tutor_profiles_avatar ON tutor_profiles(avatar_media_id)
    WHERE avatar_media_id IS NOT NULL;

COMMENT ON COLUMN tutor_profiles.avatar_media_id IS
    'The expert''s portrait. Served anonymously while the profile is APPROVED; private otherwise.';
COMMENT ON COLUMN tutor_profiles.orcid IS
    'ORCID iD in its bare 0000-0000-0000-000X form, not the orcid.org URL.';

-- =====================================================================
-- Editing any expert's profile is an admin duty distinct from approving
-- applications (tutor:approve) and from an expert editing their own
-- (tutor:manage_self), so it gets its own permission.
--
-- It is granted to SUPER_ADMIN explicitly as well as to ADMIN: V2's "SUPER_ADMIN
-- gets every permission" was a one-time cross join over the permissions that
-- existed then, so a permission created now reaches no role unless granted.
-- =====================================================================

INSERT INTO permissions (id, code, resource, action, description) VALUES
    (gen_random_uuid(), 'tutor:manage', 'tutor', 'manage',
     'Edit any expert''s profile (admin)')
ON CONFLICT (code) DO NOTHING;

INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id
FROM roles r, permissions p
WHERE r.code IN ('ADMIN', 'SUPER_ADMIN') AND p.code = 'tutor:manage'
ON CONFLICT DO NOTHING;
