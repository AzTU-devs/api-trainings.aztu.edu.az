-- =====================================================================
-- EduPlatform initial schema
-- See docs/architecture/01-erd-and-schema.md for the canonical design.
-- =====================================================================

-- Extensions ---------------------------------------------------------
CREATE EXTENSION IF NOT EXISTS pgcrypto;     -- gen_random_uuid(), digest()
CREATE EXTENSION IF NOT EXISTS citext;       -- case-insensitive email
CREATE EXTENSION IF NOT EXISTS btree_gist;   -- room booking exclusion constraint

-- =====================================================================
-- IDENTITY
-- =====================================================================

CREATE TABLE users (
    id                UUID PRIMARY KEY,
    email             CITEXT NOT NULL UNIQUE,
    phone             VARCHAR(32),
    password_hash     VARCHAR(255),
    first_name        VARCHAR(80)  NOT NULL,
    last_name         VARCHAR(80)  NOT NULL,
    avatar_media_id   UUID,
    status            VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE',
    email_verified_at TIMESTAMPTZ,
    last_login_at     TIMESTAMPTZ,
    failed_logins     SMALLINT     NOT NULL DEFAULT 0,
    locale            VARCHAR(8)   NOT NULL DEFAULT 'en',
    version           BIGINT       NOT NULL DEFAULT 0,
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    deleted_at        TIMESTAMPTZ,
    created_by        UUID,
    updated_by        UUID,
    CONSTRAINT chk_users_status CHECK (status IN ('ACTIVE','LOCKED','SUSPENDED','DELETED'))
);
CREATE INDEX idx_users_status      ON users(status) WHERE deleted_at IS NULL;
CREATE INDEX idx_users_email_lower ON users(lower(email));
CREATE INDEX idx_users_created_at  ON users(created_at DESC);

CREATE TABLE roles (
    id          UUID PRIMARY KEY,
    code        VARCHAR(40) NOT NULL UNIQUE,
    name        VARCHAR(80) NOT NULL,
    description TEXT,
    is_system   BOOLEAN NOT NULL DEFAULT FALSE,
    version     BIGINT NOT NULL DEFAULT 0,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by  UUID,
    updated_by  UUID,
    CONSTRAINT chk_roles_code CHECK (code IN ('USER','TUTOR','ADMIN','SUPER_ADMIN'))
);

CREATE TABLE permissions (
    id          UUID PRIMARY KEY,
    code        VARCHAR(80) NOT NULL UNIQUE,
    resource    VARCHAR(40) NOT NULL,
    action      VARCHAR(40) NOT NULL,
    description TEXT,
    version     BIGINT NOT NULL DEFAULT 0,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by  UUID,
    updated_by  UUID
);

CREATE TABLE role_permissions (
    role_id       UUID NOT NULL REFERENCES roles(id) ON DELETE CASCADE,
    permission_id UUID NOT NULL REFERENCES permissions(id) ON DELETE CASCADE,
    PRIMARY KEY (role_id, permission_id)
);

CREATE TABLE user_roles (
    user_id    UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    role_id    UUID NOT NULL REFERENCES roles(id) ON DELETE RESTRICT,
    granted_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    granted_by UUID,
    PRIMARY KEY (user_id, role_id)
);
CREATE INDEX idx_user_roles_role ON user_roles(role_id);

CREATE TABLE user_identities (
    id              UUID PRIMARY KEY,
    user_id         UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    provider        VARCHAR(20) NOT NULL,
    provider_user_id VARCHAR(255) NOT NULL,
    email_at_provider CITEXT,
    email_verified  BOOLEAN NOT NULL DEFAULT FALSE,
    display_name    VARCHAR(160),
    avatar_url      VARCHAR(512),
    is_private_email BOOLEAN NOT NULL DEFAULT FALSE,
    raw_profile     JSONB,
    provider_refresh_token_encrypted BYTEA,
    last_login_at   TIMESTAMPTZ,
    linked_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    version         BIGINT NOT NULL DEFAULT 0,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    deleted_at      TIMESTAMPTZ,
    created_by      UUID,
    updated_by      UUID,
    CONSTRAINT chk_identities_provider CHECK (provider IN ('LOCAL','GOOGLE','FACEBOOK','APPLE')),
    CONSTRAINT uq_identity_provider_subject UNIQUE (provider, provider_user_id),
    CONSTRAINT uq_identity_user_provider    UNIQUE (user_id, provider)
);
CREATE INDEX idx_identity_user     ON user_identities(user_id) WHERE deleted_at IS NULL;
CREATE INDEX idx_identity_provider ON user_identities(provider) WHERE deleted_at IS NULL;

CREATE TABLE oauth_auth_states (
    state               CHAR(64) PRIMARY KEY,
    provider            VARCHAR(20) NOT NULL,
    code_verifier       VARCHAR(128) NOT NULL,
    nonce               VARCHAR(128),
    redirect_uri        VARCHAR(512) NOT NULL,
    intent              VARCHAR(20) NOT NULL DEFAULT 'LOGIN',
    link_user_id        UUID REFERENCES users(id) ON DELETE CASCADE,
    post_login_redirect VARCHAR(512),
    ip_address          INET,
    user_agent          VARCHAR(255),
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at          TIMESTAMPTZ NOT NULL,
    CONSTRAINT chk_oauth_state_provider CHECK (provider IN ('GOOGLE','FACEBOOK','APPLE')),
    CONSTRAINT chk_oauth_state_intent   CHECK (intent IN ('LOGIN','LINK'))
);
CREATE INDEX idx_oauth_state_expires ON oauth_auth_states(expires_at);

CREATE TABLE refresh_tokens (
    id            UUID PRIMARY KEY,
    user_id       UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    token_hash    CHAR(64) NOT NULL UNIQUE,
    family_id     UUID NOT NULL,
    parent_id     UUID REFERENCES refresh_tokens(id) ON DELETE SET NULL,
    issued_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at    TIMESTAMPTZ NOT NULL,
    revoked_at    TIMESTAMPTZ,
    revoke_reason VARCHAR(40),
    ip_address    INET,
    user_agent    VARCHAR(255),
    CONSTRAINT chk_refresh_reason CHECK (revoke_reason IS NULL
        OR revoke_reason IN ('ROTATED','LOGOUT','REUSE_DETECTED','ADMIN'))
);
CREATE INDEX idx_refresh_user_active ON refresh_tokens(user_id) WHERE revoked_at IS NULL;
CREATE INDEX idx_refresh_family      ON refresh_tokens(family_id);
CREATE INDEX idx_refresh_expires     ON refresh_tokens(expires_at);

-- =====================================================================
-- MEDIA  (referenced by users.avatar_media_id and many others)
-- =====================================================================

CREATE TABLE media_files (
    id              UUID PRIMARY KEY,
    owner_user_id   UUID REFERENCES users(id) ON DELETE SET NULL,
    storage         VARCHAR(20) NOT NULL DEFAULT 'S3',
    bucket          VARCHAR(120),
    object_key      VARCHAR(512) NOT NULL,
    mime_type       VARCHAR(120) NOT NULL,
    byte_size       BIGINT NOT NULL,
    checksum_sha256 CHAR(64),
    width           INTEGER,
    height          INTEGER,
    duration_sec    INTEGER,
    status          VARCHAR(20) NOT NULL DEFAULT 'UPLOADED',
    visibility      VARCHAR(20) NOT NULL DEFAULT 'PRIVATE',
    metadata        JSONB,
    version         BIGINT NOT NULL DEFAULT 0,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    deleted_at      TIMESTAMPTZ,
    created_by      UUID,
    updated_by      UUID,
    CONSTRAINT chk_media_storage   CHECK (storage IN ('S3','LOCAL','CDN')),
    CONSTRAINT chk_media_status    CHECK (status IN ('PENDING','UPLOADED','PROCESSING','READY','FAILED')),
    CONSTRAINT chk_media_visibility CHECK (visibility IN ('PRIVATE','PUBLIC','SIGNED'))
);
CREATE INDEX idx_media_owner ON media_files(owner_user_id) WHERE deleted_at IS NULL;

ALTER TABLE users
    ADD CONSTRAINT fk_users_avatar
    FOREIGN KEY (avatar_media_id) REFERENCES media_files(id) ON DELETE SET NULL;

-- =====================================================================
-- CATALOG
-- =====================================================================

CREATE TABLE categories (
    id          UUID PRIMARY KEY,
    parent_id   UUID REFERENCES categories(id) ON DELETE RESTRICT,
    slug        VARCHAR(120) NOT NULL UNIQUE,
    name        VARCHAR(120) NOT NULL,
    description TEXT,
    icon_url    VARCHAR(255),
    sort_order  INTEGER NOT NULL DEFAULT 0,
    is_active   BOOLEAN NOT NULL DEFAULT TRUE,
    version     BIGINT NOT NULL DEFAULT 0,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    deleted_at  TIMESTAMPTZ,
    created_by  UUID,
    updated_by  UUID
);
CREATE INDEX idx_categories_parent ON categories(parent_id) WHERE deleted_at IS NULL;
CREATE INDEX idx_categories_active ON categories(is_active) WHERE deleted_at IS NULL;

CREATE TABLE tags (
    id          UUID PRIMARY KEY,
    slug        VARCHAR(60) NOT NULL UNIQUE,
    name        VARCHAR(60) NOT NULL,
    version     BIGINT NOT NULL DEFAULT 0,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by  UUID,
    updated_by  UUID
);

-- =====================================================================
-- TUTOR
-- =====================================================================

CREATE TABLE tutor_profiles (
    id               UUID PRIMARY KEY,
    user_id          UUID NOT NULL UNIQUE REFERENCES users(id) ON DELETE CASCADE,
    headline         VARCHAR(160),
    bio              TEXT,
    years_experience SMALLINT,
    website_url      VARCHAR(255),
    linkedin_url     VARCHAR(255),
    approval_status  VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    approved_at      TIMESTAMPTZ,
    approved_by      UUID REFERENCES users(id) ON DELETE SET NULL,
    rejection_reason TEXT,
    rating_avg       NUMERIC(3,2) NOT NULL DEFAULT 0.00,
    rating_count     INTEGER NOT NULL DEFAULT 0,
    version          BIGINT NOT NULL DEFAULT 0,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    deleted_at       TIMESTAMPTZ,
    created_by       UUID,
    updated_by       UUID,
    CONSTRAINT chk_tutor_status CHECK (approval_status IN ('PENDING','APPROVED','REJECTED','SUSPENDED'))
);
CREATE INDEX idx_tutor_approval ON tutor_profiles(approval_status) WHERE deleted_at IS NULL;

CREATE TABLE tutor_expertises (
    tutor_id    UUID NOT NULL REFERENCES tutor_profiles(id) ON DELETE CASCADE,
    category_id UUID NOT NULL REFERENCES categories(id) ON DELETE RESTRICT,
    PRIMARY KEY (tutor_id, category_id)
);
CREATE INDEX idx_tutor_expertises_cat ON tutor_expertises(category_id);

CREATE TABLE tutor_approval_requests (
    id            UUID PRIMARY KEY,
    tutor_id      UUID NOT NULL REFERENCES tutor_profiles(id) ON DELETE CASCADE,
    status        VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    decision_note TEXT,
    decided_by    UUID REFERENCES users(id) ON DELETE SET NULL,
    decided_at    TIMESTAMPTZ,
    submitted_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    version       BIGINT NOT NULL DEFAULT 0,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by    UUID,
    updated_by    UUID,
    CONSTRAINT chk_tutor_appr_status CHECK (status IN ('PENDING','APPROVED','REJECTED'))
);
CREATE INDEX idx_tutor_appr_status ON tutor_approval_requests(status, submitted_at DESC);

-- =====================================================================
-- COURSE
-- =====================================================================

CREATE TABLE courses (
    id                 UUID PRIMARY KEY,
    tutor_id           UUID NOT NULL REFERENCES tutor_profiles(id) ON DELETE RESTRICT,
    slug               VARCHAR(160) NOT NULL UNIQUE,
    title              VARCHAR(160) NOT NULL,
    subtitle           VARCHAR(255),
    description        TEXT,
    requirements       TEXT,
    learning_outcomes  TEXT,
    syllabus           TEXT,
    thumbnail_media_id UUID REFERENCES media_files(id) ON DELETE SET NULL,
    trailer_media_id   UUID REFERENCES media_files(id) ON DELETE SET NULL,
    course_type        VARCHAR(10)  NOT NULL,
    level              VARCHAR(20)  NOT NULL DEFAULT 'ALL',
    language           VARCHAR(8)   NOT NULL DEFAULT 'en',
    is_free            BOOLEAN      NOT NULL DEFAULT FALSE,
    price              NUMERIC(12,2) NOT NULL DEFAULT 0,
    currency           CHAR(3)      NOT NULL DEFAULT 'USD',
    status             VARCHAR(20)  NOT NULL DEFAULT 'DRAFT',
    rejection_reason   TEXT,
    approved_at        TIMESTAMPTZ,
    approved_by        UUID REFERENCES users(id) ON DELETE SET NULL,
    published_at       TIMESTAMPTZ,
    rating_avg         NUMERIC(3,2) NOT NULL DEFAULT 0.00,
    rating_count       INTEGER NOT NULL DEFAULT 0,
    enrolled_count     INTEGER NOT NULL DEFAULT 0,
    version            BIGINT NOT NULL DEFAULT 0,
    created_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    deleted_at         TIMESTAMPTZ,
    created_by         UUID,
    updated_by         UUID,
    CONSTRAINT chk_course_type   CHECK (course_type IN ('ONLINE','OFFLINE')),
    CONSTRAINT chk_course_level  CHECK (level IN ('BEGINNER','INTERMEDIATE','ADVANCED','ALL')),
    CONSTRAINT chk_course_status CHECK (status IN ('DRAFT','IN_REVIEW','PUBLISHED','REJECTED','ARCHIVED')),
    CONSTRAINT chk_course_price  CHECK ((is_free AND price = 0) OR (NOT is_free AND price >= 0))
);
CREATE INDEX idx_courses_status_pub ON courses(status, published_at DESC) WHERE deleted_at IS NULL;
CREATE INDEX idx_courses_tutor      ON courses(tutor_id) WHERE deleted_at IS NULL;
CREATE INDEX idx_courses_type       ON courses(course_type) WHERE deleted_at IS NULL;
CREATE INDEX idx_courses_search     ON courses USING GIN (
    to_tsvector('simple', title || ' ' || coalesce(subtitle,'') || ' ' || coalesce(description,''))
);

CREATE TABLE course_categories (
    course_id   UUID NOT NULL REFERENCES courses(id) ON DELETE CASCADE,
    category_id UUID NOT NULL REFERENCES categories(id) ON DELETE RESTRICT,
    PRIMARY KEY (course_id, category_id)
);
CREATE INDEX idx_course_categories_cat ON course_categories(category_id);

CREATE TABLE course_tags (
    course_id UUID NOT NULL REFERENCES courses(id) ON DELETE CASCADE,
    tag_id    UUID NOT NULL REFERENCES tags(id) ON DELETE RESTRICT,
    PRIMARY KEY (course_id, tag_id)
);
CREATE INDEX idx_course_tags_tag ON course_tags(tag_id);

CREATE TABLE online_course_details (
    course_id           UUID PRIMARY KEY REFERENCES courses(id) ON DELETE CASCADE,
    total_video_seconds INTEGER NOT NULL DEFAULT 0,
    has_certificate     BOOLEAN NOT NULL DEFAULT FALSE,
    drip_enabled        BOOLEAN NOT NULL DEFAULT FALSE
);

CREATE TABLE offline_course_details (
    course_id      UUID PRIMARY KEY REFERENCES courses(id) ON DELETE CASCADE,
    start_date     DATE NOT NULL,
    end_date       DATE NOT NULL,
    weekly_hours   NUMERIC(4,1),
    total_hours    NUMERIC(6,1),
    student_limit  INTEGER NOT NULL,
    enrolled_count INTEGER NOT NULL DEFAULT 0,
    city           VARCHAR(80),
    address_line   VARCHAR(255),
    CONSTRAINT chk_offline_dates CHECK (end_date >= start_date),
    CONSTRAINT chk_offline_limit CHECK (student_limit > 0)
);

CREATE TABLE course_modules (
    id          UUID PRIMARY KEY,
    course_id   UUID NOT NULL REFERENCES courses(id) ON DELETE CASCADE,
    title       VARCHAR(160) NOT NULL,
    description TEXT,
    order_index INTEGER NOT NULL,
    version     BIGINT NOT NULL DEFAULT 0,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    deleted_at  TIMESTAMPTZ,
    created_by  UUID,
    updated_by  UUID,
    CONSTRAINT uq_course_modules_order UNIQUE (course_id, order_index)
);
CREATE INDEX idx_modules_course ON course_modules(course_id, order_index) WHERE deleted_at IS NULL;

CREATE TABLE lessons (
    id               UUID PRIMARY KEY,
    module_id        UUID NOT NULL REFERENCES course_modules(id) ON DELETE CASCADE,
    title            VARCHAR(200) NOT NULL,
    description      TEXT,
    content_type     VARCHAR(20) NOT NULL DEFAULT 'VIDEO',
    video_media_id   UUID REFERENCES media_files(id) ON DELETE SET NULL,
    video_url        VARCHAR(512),
    duration_seconds INTEGER NOT NULL DEFAULT 0,
    order_index      INTEGER NOT NULL,
    is_preview       BOOLEAN NOT NULL DEFAULT FALSE,
    version          BIGINT NOT NULL DEFAULT 0,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    deleted_at       TIMESTAMPTZ,
    created_by       UUID,
    updated_by       UUID,
    CONSTRAINT chk_lesson_type CHECK (content_type IN ('VIDEO','TEXT','PDF','QUIZ','LIVE_SESSION')),
    CONSTRAINT uq_lessons_module_order UNIQUE (module_id, order_index)
);
CREATE INDEX idx_lessons_module ON lessons(module_id, order_index) WHERE deleted_at IS NULL;

CREATE TABLE lesson_media (
    lesson_id UUID NOT NULL REFERENCES lessons(id) ON DELETE CASCADE,
    media_id  UUID NOT NULL REFERENCES media_files(id) ON DELETE RESTRICT,
    role      VARCHAR(30) NOT NULL DEFAULT 'ATTACHMENT',
    PRIMARY KEY (lesson_id, media_id, role),
    CONSTRAINT chk_lesson_media_role CHECK (role IN ('ATTACHMENT','CAPTION','TRANSCRIPT'))
);

-- =====================================================================
-- ROOM
-- =====================================================================

CREATE TABLE rooms (
    id          UUID PRIMARY KEY,
    name        VARCHAR(120) NOT NULL,
    room_number VARCHAR(40)  NOT NULL,
    building    VARCHAR(120),
    capacity    INTEGER NOT NULL,
    description TEXT,
    status      VARCHAR(20) NOT NULL DEFAULT 'AVAILABLE',
    hourly_rate NUMERIC(12,2) NOT NULL DEFAULT 0,
    currency    CHAR(3) NOT NULL DEFAULT 'USD',
    version     BIGINT NOT NULL DEFAULT 0,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    deleted_at  TIMESTAMPTZ,
    created_by  UUID,
    updated_by  UUID,
    CONSTRAINT chk_room_status   CHECK (status IN ('AVAILABLE','MAINTENANCE','RESERVED','RETIRED')),
    CONSTRAINT chk_room_capacity CHECK (capacity > 0)
);
CREATE UNIQUE INDEX uq_rooms_building_number
    ON rooms(coalesce(building,''), room_number) WHERE deleted_at IS NULL;
CREATE INDEX idx_rooms_status ON rooms(status) WHERE deleted_at IS NULL;

CREATE TABLE room_images (
    id         UUID PRIMARY KEY,
    room_id    UUID NOT NULL REFERENCES rooms(id) ON DELETE CASCADE,
    media_id   UUID NOT NULL REFERENCES media_files(id) ON DELETE RESTRICT,
    sort_order INTEGER NOT NULL DEFAULT 0,
    is_cover   BOOLEAN NOT NULL DEFAULT FALSE,
    version    BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by UUID,
    updated_by UUID
);
CREATE INDEX idx_room_images_room ON room_images(room_id);

CREATE TABLE room_availability_slots (
    id          UUID PRIMARY KEY,
    room_id     UUID NOT NULL REFERENCES rooms(id) ON DELETE CASCADE,
    day_of_week SMALLINT NOT NULL,
    start_time  TIME NOT NULL,
    end_time    TIME NOT NULL,
    valid_from  DATE,
    valid_to    DATE,
    version     BIGINT NOT NULL DEFAULT 0,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by  UUID,
    updated_by  UUID,
    CONSTRAINT chk_avail_dow  CHECK (day_of_week BETWEEN 1 AND 7),
    CONSTRAINT chk_avail_slot CHECK (end_time > start_time)
);
CREATE INDEX idx_room_slots_room ON room_availability_slots(room_id, day_of_week);

CREATE TABLE room_pricing_rules (
    id          UUID PRIMARY KEY,
    room_id     UUID NOT NULL REFERENCES rooms(id) ON DELETE CASCADE,
    name        VARCHAR(80) NOT NULL,
    hourly_rate NUMERIC(12,2) NOT NULL,
    currency    CHAR(3) NOT NULL,
    day_of_week SMALLINT,
    start_time  TIME,
    end_time    TIME,
    valid_from  DATE,
    valid_to    DATE,
    priority    INTEGER NOT NULL DEFAULT 0,
    version     BIGINT NOT NULL DEFAULT 0,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by  UUID,
    updated_by  UUID,
    CONSTRAINT chk_price_rule_dow CHECK (day_of_week IS NULL OR day_of_week BETWEEN 1 AND 7)
);
CREATE INDEX idx_room_prices_room ON room_pricing_rules(room_id, priority DESC);

CREATE TABLE room_bookings (
    id                UUID PRIMARY KEY,
    room_id           UUID NOT NULL REFERENCES rooms(id) ON DELETE RESTRICT,
    offline_course_id UUID REFERENCES offline_course_details(course_id) ON DELETE CASCADE,
    tutor_id          UUID NOT NULL REFERENCES tutor_profiles(id) ON DELETE RESTRICT,
    starts_at         TIMESTAMPTZ NOT NULL,
    ends_at           TIMESTAMPTZ NOT NULL,
    recurrence_rule   VARCHAR(255),
    status            VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    total_fee         NUMERIC(12,2) NOT NULL DEFAULT 0,
    currency          CHAR(3) NOT NULL DEFAULT 'USD',
    version           BIGINT NOT NULL DEFAULT 0,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    deleted_at        TIMESTAMPTZ,
    created_by        UUID,
    updated_by        UUID,
    CONSTRAINT chk_booking_range  CHECK (ends_at > starts_at),
    CONSTRAINT chk_booking_status CHECK (status IN ('PENDING','APPROVED','REJECTED','CANCELLED'))
);
CREATE INDEX idx_bookings_room_time ON room_bookings(room_id, starts_at, ends_at) WHERE deleted_at IS NULL;
CREATE INDEX idx_bookings_status    ON room_bookings(status) WHERE deleted_at IS NULL;
CREATE INDEX idx_bookings_tutor     ON room_bookings(tutor_id) WHERE deleted_at IS NULL;

-- Approved bookings on the same room cannot overlap.
ALTER TABLE room_bookings
    ADD CONSTRAINT excl_room_overlap
    EXCLUDE USING gist (
        room_id WITH =,
        tstzrange(starts_at, ends_at, '[)') WITH &&
    ) WHERE (status = 'APPROVED' AND deleted_at IS NULL);

CREATE TABLE room_booking_approvals (
    id            UUID PRIMARY KEY,
    booking_id    UUID NOT NULL REFERENCES room_bookings(id) ON DELETE CASCADE,
    decision      VARCHAR(20) NOT NULL,
    decision_note TEXT,
    decided_by    UUID NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
    decided_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    version       BIGINT NOT NULL DEFAULT 0,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by    UUID,
    updated_by    UUID,
    CONSTRAINT chk_booking_appr_decision CHECK (decision IN ('APPROVED','REJECTED'))
);
CREATE INDEX idx_booking_appr_booking ON room_booking_approvals(booking_id, decided_at DESC);

-- =====================================================================
-- ENROLLMENT, PROGRESS, SESSIONS, ATTENDANCE
-- =====================================================================

CREATE TABLE enrollments (
    id               UUID PRIMARY KEY,
    user_id          UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    course_id        UUID NOT NULL REFERENCES courses(id) ON DELETE RESTRICT,
    status           VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    source           VARCHAR(20) NOT NULL DEFAULT 'PURCHASE',
    enrolled_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    completed_at     TIMESTAMPTZ,
    progress_percent SMALLINT NOT NULL DEFAULT 0,
    last_accessed_at TIMESTAMPTZ,
    order_item_id    UUID,
    version          BIGINT NOT NULL DEFAULT 0,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    deleted_at       TIMESTAMPTZ,
    created_by       UUID,
    updated_by       UUID,
    CONSTRAINT uq_enrollment_user_course UNIQUE (user_id, course_id),
    CONSTRAINT chk_enroll_status   CHECK (status IN ('PENDING_PAYMENT','ACTIVE','COMPLETED','CANCELLED','REFUNDED')),
    CONSTRAINT chk_enroll_source   CHECK (source IN ('PURCHASE','FREE','ADMIN_GRANT')),
    CONSTRAINT chk_enroll_progress CHECK (progress_percent BETWEEN 0 AND 100)
);
CREATE INDEX idx_enroll_user   ON enrollments(user_id) WHERE deleted_at IS NULL;
CREATE INDEX idx_enroll_course ON enrollments(course_id) WHERE deleted_at IS NULL;

CREATE TABLE lesson_progress (
    enrollment_id UUID NOT NULL REFERENCES enrollments(id) ON DELETE CASCADE,
    lesson_id     UUID NOT NULL REFERENCES lessons(id) ON DELETE CASCADE,
    status        VARCHAR(20) NOT NULL DEFAULT 'NOT_STARTED',
    position_sec  INTEGER NOT NULL DEFAULT 0,
    completed_at  TIMESTAMPTZ,
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (enrollment_id, lesson_id),
    CONSTRAINT chk_lp_status CHECK (status IN ('NOT_STARTED','IN_PROGRESS','COMPLETED'))
);
CREATE INDEX idx_lp_lesson ON lesson_progress(lesson_id);

CREATE TABLE offline_sessions (
    id                UUID PRIMARY KEY,
    offline_course_id UUID NOT NULL REFERENCES offline_course_details(course_id) ON DELETE CASCADE,
    booking_id        UUID REFERENCES room_bookings(id) ON DELETE SET NULL,
    session_date      DATE NOT NULL,
    starts_at         TIMESTAMPTZ NOT NULL,
    ends_at           TIMESTAMPTZ NOT NULL,
    topic             VARCHAR(200),
    status            VARCHAR(20) NOT NULL DEFAULT 'SCHEDULED',
    version           BIGINT NOT NULL DEFAULT 0,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by        UUID,
    updated_by        UUID,
    CONSTRAINT uq_offline_session_course_start UNIQUE (offline_course_id, starts_at),
    CONSTRAINT chk_session_status CHECK (status IN ('SCHEDULED','HELD','CANCELLED')),
    CONSTRAINT chk_session_range  CHECK (ends_at > starts_at)
);
CREATE INDEX idx_sessions_course_date ON offline_sessions(offline_course_id, session_date);

CREATE TABLE attendance_records (
    id            UUID PRIMARY KEY,
    session_id    UUID NOT NULL REFERENCES offline_sessions(id) ON DELETE CASCADE,
    enrollment_id UUID NOT NULL REFERENCES enrollments(id) ON DELETE CASCADE,
    status        VARCHAR(20) NOT NULL DEFAULT 'ABSENT',
    marked_by     UUID REFERENCES users(id) ON DELETE SET NULL,
    marked_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    note          TEXT,
    version       BIGINT NOT NULL DEFAULT 0,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by    UUID,
    updated_by    UUID,
    CONSTRAINT uq_attendance_session_enrollment UNIQUE (session_id, enrollment_id),
    CONSTRAINT chk_att_status CHECK (status IN ('PRESENT','ABSENT','LATE','EXCUSED'))
);
CREATE INDEX idx_attendance_enrollment ON attendance_records(enrollment_id);

-- =====================================================================
-- PAYMENT
-- =====================================================================

CREATE TABLE orders (
    id           UUID PRIMARY KEY,
    user_id      UUID NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
    order_number VARCHAR(40) NOT NULL UNIQUE,
    status       VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    subtotal     NUMERIC(12,2) NOT NULL,
    tax          NUMERIC(12,2) NOT NULL DEFAULT 0,
    discount     NUMERIC(12,2) NOT NULL DEFAULT 0,
    total        NUMERIC(12,2) NOT NULL,
    currency     CHAR(3) NOT NULL DEFAULT 'USD',
    placed_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    paid_at      TIMESTAMPTZ,
    version      BIGINT NOT NULL DEFAULT 0,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    deleted_at   TIMESTAMPTZ,
    created_by   UUID,
    updated_by   UUID,
    CONSTRAINT chk_order_status CHECK (status IN ('PENDING','PAID','FAILED','REFUNDED','CANCELLED'))
);
CREATE INDEX idx_orders_user ON orders(user_id, placed_at DESC) WHERE deleted_at IS NULL;

CREATE TABLE order_items (
    id              UUID PRIMARY KEY,
    order_id        UUID NOT NULL REFERENCES orders(id) ON DELETE CASCADE,
    item_type       VARCHAR(20) NOT NULL,
    course_id       UUID REFERENCES courses(id) ON DELETE SET NULL,
    room_booking_id UUID REFERENCES room_bookings(id) ON DELETE SET NULL,
    description     VARCHAR(255) NOT NULL,
    quantity        INTEGER NOT NULL DEFAULT 1,
    unit_price      NUMERIC(12,2) NOT NULL,
    total_price     NUMERIC(12,2) NOT NULL,
    currency        CHAR(3) NOT NULL,
    version         BIGINT NOT NULL DEFAULT 0,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by      UUID,
    updated_by      UUID,
    CONSTRAINT chk_order_item_type CHECK (item_type IN ('COURSE','ROOM_USAGE_FEE'))
);
CREATE INDEX idx_order_items_order ON order_items(order_id);

CREATE TABLE payments (
    id                  UUID PRIMARY KEY,
    order_id            UUID NOT NULL REFERENCES orders(id) ON DELETE RESTRICT,
    provider            VARCHAR(30) NOT NULL,
    provider_payment_id VARCHAR(120),
    provider_intent_id  VARCHAR(120),
    status              VARCHAR(20) NOT NULL,
    amount              NUMERIC(12,2) NOT NULL,
    currency            CHAR(3) NOT NULL,
    method              VARCHAR(30),
    error_code          VARCHAR(80),
    error_message       TEXT,
    raw_payload         JSONB,
    version             BIGINT NOT NULL DEFAULT 0,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by          UUID,
    updated_by          UUID,
    CONSTRAINT chk_payments_provider CHECK (provider IN ('STRIPE','MANUAL')),
    CONSTRAINT chk_payments_status   CHECK (status IN ('INITIATED','SUCCEEDED','FAILED','REFUNDED','PARTIALLY_REFUNDED'))
);
CREATE INDEX idx_payments_order           ON payments(order_id);
CREATE INDEX idx_payments_provider_intent ON payments(provider, provider_intent_id);

CREATE TABLE payment_events (
    id          UUID PRIMARY KEY,
    payment_id  UUID NOT NULL REFERENCES payments(id) ON DELETE CASCADE,
    event_type  VARCHAR(60) NOT NULL,
    payload     JSONB NOT NULL,
    received_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    version     BIGINT NOT NULL DEFAULT 0,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by  UUID,
    updated_by  UUID
);
CREATE INDEX idx_payment_events_payment ON payment_events(payment_id, received_at DESC);

-- =====================================================================
-- REVIEW
-- =====================================================================

CREATE TABLE course_reviews (
    id            UUID PRIMARY KEY,
    course_id     UUID NOT NULL REFERENCES courses(id) ON DELETE CASCADE,
    user_id       UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    enrollment_id UUID REFERENCES enrollments(id) ON DELETE SET NULL,
    rating        SMALLINT NOT NULL,
    title         VARCHAR(160),
    body          TEXT,
    is_visible    BOOLEAN NOT NULL DEFAULT TRUE,
    version       BIGINT NOT NULL DEFAULT 0,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    deleted_at    TIMESTAMPTZ,
    created_by    UUID,
    updated_by    UUID,
    CONSTRAINT uq_course_review_course_user UNIQUE (course_id, user_id),
    CONSTRAINT chk_review_rating CHECK (rating BETWEEN 1 AND 5)
);
CREATE INDEX idx_reviews_course ON course_reviews(course_id, created_at DESC) WHERE deleted_at IS NULL;

-- =====================================================================
-- NOTIFICATION
-- =====================================================================

CREATE TABLE notification_templates (
    id         UUID PRIMARY KEY,
    code       VARCHAR(80) NOT NULL,
    channel    VARCHAR(20) NOT NULL,
    locale     VARCHAR(8) NOT NULL DEFAULT 'en',
    subject    VARCHAR(255),
    body       TEXT NOT NULL,
    is_active  BOOLEAN NOT NULL DEFAULT TRUE,
    version    BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by UUID,
    updated_by UUID,
    CONSTRAINT uq_notif_template_code_channel_locale UNIQUE (code, channel, locale),
    CONSTRAINT chk_notif_tpl_channel CHECK (channel IN ('IN_APP','EMAIL','SMS','WEBSOCKET'))
);

CREATE TABLE notifications (
    id            UUID PRIMARY KEY,
    user_id       UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    template_code VARCHAR(80) NOT NULL,
    channel       VARCHAR(20) NOT NULL,
    title         VARCHAR(255),
    body          TEXT,
    payload       JSONB,
    status        VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    sent_at       TIMESTAMPTZ,
    read_at       TIMESTAMPTZ,
    version       BIGINT NOT NULL DEFAULT 0,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by    UUID,
    updated_by    UUID,
    CONSTRAINT chk_notif_status  CHECK (status IN ('PENDING','SENT','FAILED','READ')),
    CONSTRAINT chk_notif_channel CHECK (channel IN ('IN_APP','EMAIL','SMS','WEBSOCKET'))
);
CREATE INDEX idx_notif_user_unread ON notifications(user_id, created_at DESC) WHERE read_at IS NULL;

-- =====================================================================
-- AUDIT
-- =====================================================================

CREATE TABLE audit_logs (
    id          UUID PRIMARY KEY,
    actor_id    UUID REFERENCES users(id) ON DELETE SET NULL,
    actor_role  VARCHAR(40),
    action      VARCHAR(80) NOT NULL,
    entity_type VARCHAR(60) NOT NULL,
    entity_id   UUID,
    before_data JSONB,
    after_data  JSONB,
    ip_address  INET,
    user_agent  VARCHAR(255),
    request_id  UUID,
    occurred_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_audit_actor  ON audit_logs(actor_id, occurred_at DESC);
CREATE INDEX idx_audit_entity ON audit_logs(entity_type, entity_id, occurred_at DESC);
CREATE INDEX idx_audit_action ON audit_logs(action, occurred_at DESC);

CREATE TABLE security_events (
    id          UUID PRIMARY KEY,
    user_id     UUID REFERENCES users(id) ON DELETE SET NULL,
    event_type  VARCHAR(60) NOT NULL,
    ip_address  INET,
    user_agent  VARCHAR(255),
    detail      JSONB,
    occurred_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_sec_events_user ON security_events(user_id, occurred_at DESC);
CREATE INDEX idx_sec_events_type ON security_events(event_type, occurred_at DESC);

-- =====================================================================
-- Updated-at trigger (defensive; JPA also sets it from app layer)
-- =====================================================================

CREATE OR REPLACE FUNCTION set_updated_at() RETURNS TRIGGER AS $$
BEGIN
    NEW.updated_at = now();
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

DO $$
DECLARE r RECORD;
BEGIN
    FOR r IN
        SELECT c.table_name
        FROM information_schema.columns c
        WHERE c.table_schema = 'public'
          AND c.column_name = 'updated_at'
    LOOP
        EXECUTE format(
          'CREATE TRIGGER trg_%I_updated_at BEFORE UPDATE ON %I
           FOR EACH ROW EXECUTE FUNCTION set_updated_at();',
          r.table_name, r.table_name);
    END LOOP;
END$$;
