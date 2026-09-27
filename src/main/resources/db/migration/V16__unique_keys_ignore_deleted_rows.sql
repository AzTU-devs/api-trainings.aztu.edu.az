-- =====================================================================
-- Unique keys that soft-deleted rows no longer hold on to.
--
-- Every table here soft-deletes (deleted_at), but these four keys were
-- full UNIQUE constraints, so a deleted row kept its value for ever while
-- the application — which never sees deleted rows — believed it was free:
--
--   users.email            a deleted account's address could never register
--                          or be created again (a bare 409, and the whole row,
--                          password hash included, dumped to the error log);
--   categories.slug        a deleted category's slug could never be reused;
--   course_modules and     after any module or lesson was deleted, adding one
--   lessons order_index    collided with the deleted row's position, so a
--                          course could never grow again.
--
-- Each becomes a partial unique index over live rows only, the pattern V1
-- already uses for rooms and V4 for users.fin_kod. Safe on a populated
-- database: the new indexes enforce a subset of what the old constraints
-- did, so building them cannot fail on existing data.
-- =====================================================================

ALTER TABLE users DROP CONSTRAINT IF EXISTS users_email_key;
CREATE UNIQUE INDEX IF NOT EXISTS uq_users_email_active
    ON users (email) WHERE deleted_at IS NULL;

ALTER TABLE categories DROP CONSTRAINT IF EXISTS categories_slug_key;
CREATE UNIQUE INDEX IF NOT EXISTS uq_categories_slug_live
    ON categories (slug) WHERE deleted_at IS NULL;

ALTER TABLE course_modules DROP CONSTRAINT IF EXISTS uq_course_modules_order;
CREATE UNIQUE INDEX IF NOT EXISTS uq_course_modules_order_live
    ON course_modules (course_id, order_index) WHERE deleted_at IS NULL;

ALTER TABLE lessons DROP CONSTRAINT IF EXISTS uq_lessons_module_order;
CREATE UNIQUE INDEX IF NOT EXISTS uq_lessons_module_order_live
    ON lessons (module_id, order_index) WHERE deleted_at IS NULL;
