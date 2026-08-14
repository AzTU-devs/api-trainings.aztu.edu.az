-- =====================================================================
-- Convert fixed-length CHAR(n) columns to VARCHAR(n) so Hibernate's
-- schema validation (which maps String -> VARCHAR) matches the DB.
-- CHAR and VARCHAR have identical storage in PostgreSQL.
-- =====================================================================

ALTER TABLE courses             ALTER COLUMN currency        TYPE VARCHAR(3) USING currency::varchar(3);
ALTER TABLE rooms               ALTER COLUMN currency        TYPE VARCHAR(3) USING currency::varchar(3);
ALTER TABLE room_bookings       ALTER COLUMN currency        TYPE VARCHAR(3) USING currency::varchar(3);
ALTER TABLE room_pricing_rules  ALTER COLUMN currency        TYPE VARCHAR(3) USING currency::varchar(3);
ALTER TABLE orders              ALTER COLUMN currency        TYPE VARCHAR(3) USING currency::varchar(3);
ALTER TABLE order_items         ALTER COLUMN currency        TYPE VARCHAR(3) USING currency::varchar(3);
ALTER TABLE payments            ALTER COLUMN currency        TYPE VARCHAR(3) USING currency::varchar(3);

ALTER TABLE refresh_tokens      ALTER COLUMN token_hash      TYPE VARCHAR(64) USING token_hash::varchar(64);
ALTER TABLE media_files         ALTER COLUMN checksum_sha256 TYPE VARCHAR(64) USING checksum_sha256::varchar(64);

-- oauth_auth_states.state is the PK; need to handle that explicitly.
ALTER TABLE oauth_auth_states   ALTER COLUMN state           TYPE VARCHAR(64) USING state::varchar(64);
