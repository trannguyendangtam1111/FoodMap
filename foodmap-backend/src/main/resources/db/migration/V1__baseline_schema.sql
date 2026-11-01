-- =============================================================================
-- V1 — Baseline schema for FoodMap
--
-- This file represents the FULL initial state of all tables.
-- It is the single source of truth for the database structure.
--
-- Applied automatically by Flyway on first startup against an empty database.
-- For existing databases (dev or prod created with ddl-auto: update),
-- this file is BASELINED (marked as applied) without being re-run.
-- See baseline-on-migrate: true in application-dev.yml / application-prod.yml.
--
-- PostgreSQL version assumed: 14+
-- Encoding: UTF-8
-- =============================================================================


-- =============================================================================
-- USERS
-- Central identity table. Extended by Spring Security's UserDetails contract.
-- Role is stored as a VARCHAR enum string, not a PostgreSQL ENUM type —
-- VARCHAR is simpler to evolve (no ALTER TYPE needed to add a role).
-- =============================================================================

CREATE TABLE users (
    id            BIGSERIAL    PRIMARY KEY,
    email         VARCHAR(255) NOT NULL,
    password_hash VARCHAR(255) NOT NULL,
    display_name  VARCHAR(100) NOT NULL,
    avatar_url    VARCHAR(500),
    role          VARCHAR(20)  NOT NULL DEFAULT 'USER',
    enabled       BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at    TIMESTAMPTZ  NOT NULL DEFAULT NOW(),

    CONSTRAINT uk_users_email UNIQUE (email),

    -- DB-level guard against invalid roles.
    -- Application-level enum validation is still kept in the Java entity.
    -- Two layers of protection: application rejects bad input first,
    -- DB rejects anything that slips through.
    CONSTRAINT chk_users_role CHECK (role IN ('USER', 'ADMIN'))
);

-- Unique index covers the UNIQUE constraint lookup (email login) and
-- the index for duplicate-checking during registration.
CREATE UNIQUE INDEX idx_users_email ON users (email);

-- Non-unique index for admin queries filtering by role (e.g., list all admins).
CREATE INDEX idx_users_role ON users (role);


-- =============================================================================
-- REFRESH TOKENS
-- Server-side record of issued JWT refresh tokens.
-- Does not extend BaseEntity — only created_at is meaningful here.
-- ON DELETE CASCADE: deleting a user removes all their tokens automatically.
-- =============================================================================

CREATE TABLE refresh_tokens (
    id         BIGSERIAL     PRIMARY KEY,
    token      VARCHAR(1024) NOT NULL,
    user_id    BIGINT        NOT NULL,
    expires_at TIMESTAMPTZ   NOT NULL,
    revoked    BOOLEAN       NOT NULL DEFAULT FALSE,
    created_at TIMESTAMPTZ   NOT NULL DEFAULT NOW(),

    CONSTRAINT uk_refresh_tokens_token UNIQUE (token),
    CONSTRAINT fk_refresh_tokens_user
        FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE
);

-- Unique index on token — the primary lookup for token validation.
CREATE UNIQUE INDEX idx_refresh_tokens_token      ON refresh_tokens (token);

-- Supports "revoke all tokens for user X" queries on logout.
CREATE        INDEX idx_refresh_tokens_user_id    ON refresh_tokens (user_id);

-- Supports the cleanup job that purges expired tokens.
CREATE        INDEX idx_refresh_tokens_expires_at ON refresh_tokens (expires_at);


-- =============================================================================
-- FOOD TAGS
-- Stable reference data (tags like "Phở", "Bún", "Cay").
-- Uses SERIAL (INTEGER) PK by design — fewer than 2^31 tags will ever exist.
-- No timestamps — tags are effectively immutable reference data.
-- =============================================================================

CREATE TABLE food_tags (
    id   SERIAL      PRIMARY KEY,
    name VARCHAR(50) NOT NULL,
    slug VARCHAR(60) NOT NULL,

    CONSTRAINT uk_food_tags_name UNIQUE (name),
    CONSTRAINT uk_food_tags_slug UNIQUE (slug)
);

CREATE UNIQUE INDEX idx_food_tags_slug ON food_tags (slug);


-- =============================================================================
-- FOODS
-- Canonical food/dish catalogue. Foods are global — they exist independently
-- of restaurants. The restaurant-specific details live in restaurant_foods.
-- =============================================================================

CREATE TABLE foods (
    id               BIGSERIAL      PRIMARY KEY,
    name             VARCHAR(150)   NOT NULL,
    slug             VARCHAR(160)   NOT NULL,
    description      TEXT,
    cuisine          VARCHAR(100),
    photo_url        VARCHAR(500),
    -- Denormalized score updated by the trending computation job.
    -- DEFAULT 0 matches the @Builder.Default in the entity.
    popularity_score NUMERIC(10, 3) NOT NULL DEFAULT 0,
    created_at       TIMESTAMPTZ    NOT NULL DEFAULT NOW(),
    updated_at       TIMESTAMPTZ    NOT NULL DEFAULT NOW(),

    CONSTRAINT uk_foods_slug UNIQUE (slug)
);

CREATE UNIQUE INDEX idx_foods_slug             ON foods (slug);
CREATE        INDEX idx_foods_popularity_score ON foods (popularity_score);


-- =============================================================================
-- FOOD TAGS JOIN
-- @ManyToMany join table between foods and food_tags.
-- Composite PK (food_id, tag_id) enforces uniqueness without a surrogate key.
-- ON DELETE CASCADE on both sides: removing a food or tag cleans up the join rows.
-- =============================================================================

CREATE TABLE food_tags_join (
    food_id BIGINT  NOT NULL,
    tag_id  INTEGER NOT NULL,

    CONSTRAINT pk_food_tags_join
        PRIMARY KEY (food_id, tag_id),

    CONSTRAINT fk_ftj_food
        FOREIGN KEY (food_id) REFERENCES foods (id) ON DELETE CASCADE,

    CONSTRAINT fk_ftj_tag
        FOREIGN KEY (tag_id) REFERENCES food_tags (id) ON DELETE CASCADE
);

-- No extra indexes needed: the composite PK already creates an index on
-- (food_id, tag_id). Lookups by tag_id alone (find all foods with tag X)
-- need a separate index because the PK index is food_id-leading.
CREATE INDEX idx_ftj_tag_id ON food_tags_join (tag_id);


-- =============================================================================
-- RESTAURANTS
-- Cached data from the Goong Places API plus FoodMap business fields.
-- goong_place_id is the external idempotency key used for upserts.
--
-- JSON columns (opening_hours, photos, provider_raw) are stored as TEXT.
-- The JPA converters (StringMapConverter, StringListConverter) serialize/
-- deserialize them as Java Map/List. TEXT is compatible with Hibernate's
-- validate mode. A future migration can ALTER these to JSONB for index support.
-- =============================================================================

CREATE TABLE restaurants (
    id             BIGSERIAL        PRIMARY KEY,
    goong_place_id VARCHAR(128)     NOT NULL,
    name           VARCHAR(255)     NOT NULL,
    address        VARCHAR(500),
    latitude       DOUBLE PRECISION NOT NULL,
    longitude      DOUBLE PRECISION NOT NULL,
    phone          VARCHAR(50),
    website        VARCHAR(500),
    opening_hours  TEXT,                        -- JSON: {"monday": "06:00-22:00", ...}
    photos         TEXT,                        -- JSON: ["https://...", ...]
    provider_raw   TEXT,                        -- raw Goong API response payload
    cuisine_type   VARCHAR(100),
    price_level    INTEGER,
    avg_rating     NUMERIC(3, 2)    NOT NULL DEFAULT 0,
    review_count   INTEGER          NOT NULL DEFAULT 0,
    view_count     BIGINT           NOT NULL DEFAULT 0,
    is_active      BOOLEAN          NOT NULL DEFAULT TRUE,
    last_synced_at TIMESTAMPTZ,
    created_at     TIMESTAMPTZ      NOT NULL DEFAULT NOW(),
    updated_at     TIMESTAMPTZ      NOT NULL DEFAULT NOW(),

    CONSTRAINT uk_restaurants_goong_place_id UNIQUE (goong_place_id),

    -- 1=budget, 2=moderate, 3=upscale, 4=fine dining — matches entity comment.
    CONSTRAINT chk_restaurants_price_level
        CHECK (price_level IS NULL OR price_level BETWEEN 1 AND 4),

    -- avg_rating is a denormalized average of 1–5 reviews, so valid range is [0, 5].
    CONSTRAINT chk_restaurants_avg_rating
        CHECK (avg_rating >= 0 AND avg_rating <= 5)
);

CREATE UNIQUE INDEX idx_restaurants_goong_place_id ON restaurants (goong_place_id);

-- Bounding-box spatial query: WHERE latitude BETWEEN x AND y AND longitude BETWEEN a AND b
-- Both columns in one index allows the planner to use an index-only scan for the viewport query.
CREATE INDEX idx_restaurants_lat_lng        ON restaurants (latitude, longitude);
CREATE INDEX idx_restaurants_avg_rating     ON restaurants (avg_rating);
CREATE INDEX idx_restaurants_cuisine_type   ON restaurants (cuisine_type);
CREATE INDEX idx_restaurants_last_synced_at ON restaurants (last_synced_at);
CREATE INDEX idx_restaurants_is_active      ON restaurants (is_active);


-- =============================================================================
-- RESTAURANT FOODS
-- Join entity between Restaurant and Food.
-- Uses a surrogate BIGSERIAL PK for simpler querying.
-- Business uniqueness enforced by uk_restaurant_food.
-- ON DELETE CASCADE on both sides: consistent with parent-child semantics.
-- =============================================================================

CREATE TABLE restaurant_foods (
    id            BIGSERIAL      PRIMARY KEY,
    restaurant_id BIGINT         NOT NULL,
    food_id       BIGINT         NOT NULL,
    price         NUMERIC(12, 2),
    is_signature  BOOLEAN        NOT NULL DEFAULT FALSE,
    created_at    TIMESTAMPTZ    NOT NULL DEFAULT NOW(),

    CONSTRAINT uk_restaurant_food UNIQUE (restaurant_id, food_id),

    CONSTRAINT fk_rf_restaurant
        FOREIGN KEY (restaurant_id) REFERENCES restaurants (id) ON DELETE CASCADE,

    CONSTRAINT fk_rf_food
        FOREIGN KEY (food_id) REFERENCES foods (id) ON DELETE CASCADE
);

-- Covers FK lookups from the foods side (find all restaurants serving food X).
CREATE INDEX idx_rf_food_id ON restaurant_foods (food_id);

-- Covers the signature-dish filter on the restaurant detail page.
CREATE INDEX idx_rf_restaurant_signature ON restaurant_foods (restaurant_id, is_signature);


-- =============================================================================
-- REVIEWS
-- User-written reviews for a restaurant, optionally targeting a specific dish.
--
-- CRITICAL: food_id uses ON DELETE SET NULL.
-- This is intentional and declared in the Java entity via @ForeignKey(name = "fk_review_food").
-- JPA @ManyToOne alone does NOT generate ON DELETE SET NULL — it must be
-- written explicitly in SQL. Without this, deleting a Food row that has
-- reviews would raise a FK violation and crash the application.
-- =============================================================================

CREATE TABLE reviews (
    id            BIGSERIAL   PRIMARY KEY,
    user_id       BIGINT      NOT NULL,
    restaurant_id BIGINT      NOT NULL,
    food_id       BIGINT,                   -- nullable: null = general restaurant review
    rating        INTEGER     NOT NULL,
    comment       TEXT,
    photos        TEXT,                     -- JSON: ["https://...", ...]
    created_at    TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    -- Rating must be in the 1-5 range.
    -- Application-level @Min(1) @Max(5) is the first line of defence;
    -- this constraint is the safety net at the DB layer.
    CONSTRAINT chk_reviews_rating CHECK (rating BETWEEN 1 AND 5),

    CONSTRAINT fk_review_user
        FOREIGN KEY (user_id)       REFERENCES users       (id) ON DELETE CASCADE,

    CONSTRAINT fk_review_restaurant
        FOREIGN KEY (restaurant_id) REFERENCES restaurants (id) ON DELETE CASCADE,

    -- ON DELETE SET NULL: if a food item is removed, the review text is preserved
    -- but food_id becomes NULL (review becomes a general restaurant review).
    CONSTRAINT fk_review_food
        FOREIGN KEY (food_id)       REFERENCES foods        (id) ON DELETE SET NULL
);

-- Individual FK indexes for JOIN performance.
CREATE INDEX idx_reviews_restaurant_id ON reviews (restaurant_id);
CREATE INDEX idx_reviews_user_id       ON reviews (user_id);
CREATE INDEX idx_reviews_food_id       ON reviews (food_id);

-- Composite index for the most common query pattern:
-- "get reviews for restaurant X, paginated by newest first"
CREATE INDEX idx_reviews_restaurant_created_at ON reviews (restaurant_id, created_at);


-- =============================================================================
-- TRENDING SCORES
-- Materialized output of the trending computation job.
-- entity_id has NO FK constraint by design — it references either foods.id or
-- restaurants.id depending on entity_type. Integrity is the computation job's
-- responsibility, not the DB's.
--
-- UNIQUE CONSTRAINT DESIGN:
-- The business key is (entity_type, entity_id, time_window, region_key).
-- region_key IS NULL means "global score" (not scoped to any city/region).
--
-- Standard UNIQUE constraints treat NULL as "unknown" — two NULLs are NOT
-- considered equal. This means UNIQUE(a, b, c, NULL) allows duplicates when
-- the fourth column is NULL, which would allow two "global" scores for the
-- same entity/window.
--
-- Fix: two partial unique indexes instead of one table-level UNIQUE constraint.
--   - One partial index for regional scores (region_key IS NOT NULL)
--   - One partial index for global scores   (region_key IS NULL)
-- This enforces: exactly one global score per entity/window, and exactly one
-- regional score per entity/window/region.
--
-- NOTE: The JPA entity declares @UniqueConstraint with the same name.
-- Hibernate validate mode in Spring Boot 3.x does not check partial index
-- definitions — it validates column existence and nullability only.
-- The partial indexes below replace the table-level constraint.
-- =============================================================================

CREATE TABLE trending_scores (
    id          BIGSERIAL      PRIMARY KEY,
    entity_type VARCHAR(20)    NOT NULL,
    entity_id   BIGINT         NOT NULL,
    score       NUMERIC(12, 4) NOT NULL,
    time_window VARCHAR(10)    NOT NULL,
    region_key  VARCHAR(50),              -- NULL = global score
    computed_at TIMESTAMPTZ    NOT NULL,

    CONSTRAINT chk_trending_entity_type
        CHECK (entity_type IN ('FOOD', 'RESTAURANT')),

    CONSTRAINT chk_trending_time_window
        CHECK (time_window IN ('H24', 'D7', 'D30'))
);

-- Uniqueness for regional scores: (entity_type, entity_id, time_window, region_key)
-- Only fires when region_key is non-null.
CREATE UNIQUE INDEX uk_trending_entity_window_region
    ON trending_scores (entity_type, entity_id, time_window, region_key)
    WHERE region_key IS NOT NULL;

-- Uniqueness for global scores: (entity_type, entity_id, time_window)
-- Only fires when region_key is null (global score).
CREATE UNIQUE INDEX uk_trending_entity_window_global
    ON trending_scores (entity_type, entity_id, time_window)
    WHERE region_key IS NULL;

-- Main read index: "get top N trending foods in window=H24, region=hcmc, ordered by score DESC"
CREATE INDEX idx_trending_lookup
    ON trending_scores (entity_type, time_window, region_key, score);

-- Supports the cleanup job that purges old computation runs.
CREATE INDEX idx_trending_computed_at ON trending_scores (computed_at);
