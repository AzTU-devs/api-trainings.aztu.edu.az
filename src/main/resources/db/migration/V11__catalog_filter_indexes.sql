-- Indexes for the public catalog's filter set.
--
-- /api/public/courses grew from "type + category" to the full filter surface the
-- site always showed but previously applied in the browser, on one page of
-- results at a time: q, type, categoryId, level, language, free, priceMin,
-- priceMax, ratingMin, durationBucket. Those predicates now run in SQL, so the
-- columns behind them need to be indexed or every catalog page becomes a seq
-- scan over `courses` plus a sort.
--
-- What already exists (V1__init) and is deliberately NOT duplicated here:
--   idx_courses_status_pub  (status, published_at DESC) WHERE deleted_at IS NULL
--   idx_courses_type        (course_type)               WHERE deleted_at IS NULL
--   idx_courses_search      GIN over to_tsvector('simple', title|subtitle|description)
--   idx_course_categories_cat (category_id)

-- One partial composite rather than six single-column indexes.
--
-- Partial, because the public catalog reads exactly one slice of this table —
-- published, not soft-deleted — and that slice is a small fraction of all rows
-- once drafts, rejected and archived courses accumulate. Restricting the index to
-- it keeps it small enough to stay cached, and lets Postgres use it without
-- re-checking status/deleted_at.
--
-- Composite, because the filters arrive in combination. Postgres can use any
-- leading prefix of the column list, and can still index-scan on the trailing
-- columns, so this one index serves "online + beginner", "free only",
-- "price 0..50" and "rating 4+" without six separate structures to write on every
-- course update.
--
-- Column order is most-selective-and-most-used first. course_type and level are
-- the two controls a visitor reaches for first; price and rating_avg come last
-- because they are range predicates, which cannot be followed by further
-- index-ordered columns anyway. published_at trails so the catalog's default
-- ordering can be satisfied from the index.
CREATE INDEX IF NOT EXISTS idx_courses_catalog
    ON courses (course_type, level, language, is_free, price, rating_avg, published_at DESC)
    WHERE deleted_at IS NULL AND status = 'PUBLISHED';

-- durationBucket filters on a value that lives in the per-type detail tables, not
-- on `courses`: total_video_seconds for ONLINE, total_hours for OFFLINE. Both are
-- joined 1:1 from the catalog query, so index the filtered column on each side.
CREATE INDEX IF NOT EXISTS idx_online_details_duration
    ON online_course_details (total_video_seconds);

CREATE INDEX IF NOT EXISTS idx_offline_details_hours
    ON offline_course_details (total_hours);

-- NOTE on the full-text index: idx_courses_search is an index on the EXPRESSION
--   to_tsvector('simple', title || ' ' || coalesce(subtitle,'') || ' ' || coalesce(description,''))
-- An expression index is only used when the query's expression matches it
-- character for character — same 'simple' configuration, same column order, same
-- coalesce wrapping. If the catalog query's tsvector is ever reworded, this index
-- silently stops being used and `q` searches degrade to a seq scan with no error
-- anywhere. Change the two together, and confirm with EXPLAIN that the GIN scan is
-- still chosen.
