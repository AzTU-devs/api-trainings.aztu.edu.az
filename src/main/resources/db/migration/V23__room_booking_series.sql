-- =====================================================================
-- Long-term room bookings ("booking series"): an expert asks once for a
-- room on chosen weekdays, between two times, over a period of up to a
-- year, and an admin approves or rejects the whole request at once.
--
-- Every occurrence stays an ordinary room_bookings row, linked to its
-- series by series_id. excl_room_overlap therefore guards each occurrence
-- exactly as it guards a single booking, and everything else that reads
-- room_bookings (room deletion, account retirement, utilisation, the
-- calendars) sees the occurrences without any change.
--
-- Purely additive: a new table, and a nullable column without a default
-- on room_bookings, which Postgres adds without rewriting the table.
-- Safe to run twice: every statement is guarded.
-- =====================================================================

CREATE TABLE IF NOT EXISTS room_booking_series (
    id                UUID PRIMARY KEY,
    room_id           UUID NOT NULL REFERENCES rooms(id) ON DELETE RESTRICT,
    -- Same meaning, and same cascade, as room_bookings.offline_course_id.
    offline_course_id UUID REFERENCES offline_course_details(course_id) ON DELETE CASCADE,
    tutor_id          UUID NOT NULL REFERENCES tutor_profiles(id) ON DELETE RESTRICT,
    start_date        DATE NOT NULL,
    end_date          DATE NOT NULL,
    -- The weekdays as a bit mask in ISO order: bit 0 = Monday … bit 6 =
    -- Sunday, i.e. 1 << (java.time.DayOfWeek.getValue() - 1). Pricing rules
    -- number days from Sunday = 0 instead; the two never meet in SQL.
    days_of_week      SMALLINT NOT NULL,
    start_time        TIME NOT NULL,
    end_time          TIME NOT NULL,
    -- The zone the dates and times were expanded in (app.timezone when the
    -- request was made), so the stored occurrences still explain themselves
    -- if the setting ever changes.
    timezone          VARCHAR(64) NOT NULL,
    status            VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    -- The fee quoted when the series was requested: the sum of its
    -- occurrences' fees, all in one currency.
    total_fee         NUMERIC(12,2) NOT NULL DEFAULT 0,
    currency          VARCHAR(3) NOT NULL,
    note              TEXT,
    decision_note     TEXT,
    decided_by        UUID REFERENCES users(id) ON DELETE RESTRICT,
    decided_at        TIMESTAMPTZ,
    version           BIGINT NOT NULL DEFAULT 0,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    deleted_at        TIMESTAMPTZ,
    created_by        UUID,
    updated_by        UUID,
    CONSTRAINT chk_series_dates  CHECK (end_date >= start_date),
    CONSTRAINT chk_series_times  CHECK (end_time > start_time),
    CONSTRAINT chk_series_days   CHECK (days_of_week BETWEEN 1 AND 127),
    CONSTRAINT chk_series_status CHECK (status IN ('PENDING','APPROVED','REJECTED','CANCELLED'))
);

-- The expert's list (newest first) and the admin queue (oldest first).
CREATE INDEX IF NOT EXISTS idx_series_tutor  ON room_booking_series(tutor_id, created_at) WHERE deleted_at IS NULL;
CREATE INDEX IF NOT EXISTS idx_series_status ON room_booking_series(status, created_at)   WHERE deleted_at IS NULL;

-- NULL for a single booking. No cascade: nothing deletes a series row, and
-- a course whose details go cascades to the series and its occurrences
-- alike through their own offline_course_id.
ALTER TABLE room_bookings
    ADD COLUMN IF NOT EXISTS series_id UUID
        CONSTRAINT fk_bookings_series REFERENCES room_booking_series(id);
CREATE INDEX IF NOT EXISTS idx_bookings_series ON room_bookings(series_id, starts_at) WHERE series_id IS NOT NULL;
