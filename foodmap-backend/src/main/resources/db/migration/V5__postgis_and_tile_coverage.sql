-- =============================================================================
-- V5 — PostGIS spatial layer + tile-based coverage tracking
--
-- WHY THIS MIGRATION EXISTS
--   Phase 1 audit showed:
--     - viewport reads use a composite btree on (latitude, longitude). This
--       works for "WHERE lat BETWEEN ... AND lng BETWEEN ..." but degenerates
--       for KNN ("nearest 50 restaurants to point X") and distance ORDER BY.
--     - there is no record of which geographic areas have ever been ingested
--       from Goong, so the system has no way to decide "this viewport is new,
--       enqueue a background sync" vs "this viewport is already covered."
--     - duplicate handling relies entirely on goong_place_id. When a place
--       appears in two responses with slightly different IDs, or when we add
--       a second provider later, we have no fallback fingerprint.
--
--   This migration adds the three primitives the discovery pipeline needs:
--     1. A PostGIS Point geometry + GIST index so spatial queries become
--        index-driven for envelope, distance, and KNN shapes.
--     2. A `place_cells` table that records, per geohash-6 cell, when we last
--        synced it and how many places we found. This is the coverage ledger
--        the read path consults before enqueuing background ingestion.
--     3. A `dedup_fingerprint` column on restaurants — sha1(name + rounded
--        coords) — as a secondary dedup key for future-proofing.
--
-- POSTGIS REQUIREMENT
--   CREATE EXTENSION postgis requires superuser / rds_superuser. On managed
--   Postgres (Supabase / Neon / RDS) it is usually pre-installed; locally on
--   Windows you typically install the "PostGIS Bundle" alongside PostgreSQL.
--   The Flyway error message is clear if the extension cannot be created.
-- =============================================================================


-- ── 1. PostGIS extension ────────────────────────────────────────────────────
CREATE EXTENSION IF NOT EXISTS postgis;


-- ── 2. Generated geometry column on restaurants ─────────────────────────────
--
-- We keep latitude/longitude as the source of truth (entities, mappers, and the
-- frontend all speak lat/lng). `geom` is GENERATED ALWAYS so the application
-- code never touches it — Postgres computes and maintains it for free on every
-- INSERT/UPDATE, and there is no drift risk.
--
-- SRID 4326 = WGS84, the lat/lng coordinate system every browser geolocation
-- API and every map provider returns. ST_MakePoint takes (lng, lat) — note
-- the order — which trips up everyone the first time.
--
-- STORED (not VIRTUAL) is required so we can index it. Postgres only added
-- generated columns in 12; "STORED" is the only flavour supported, so this
-- is just an explicit declaration.
ALTER TABLE restaurants
    ADD COLUMN geom geometry(Point, 4326)
    GENERATED ALWAYS AS (ST_SetSRID(ST_MakePoint(longitude, latitude), 4326)) STORED;


-- ── 3. GIST index on geom ───────────────────────────────────────────────────
--
-- GIST is the index access method PostGIS ships geometry operators against:
--   - && (bbox overlap)       → viewport envelope reads
--   - <-> (KNN distance)      → "nearest N restaurants to user"
--   - ST_DWithin              → "all restaurants within R metres of point"
--
-- Without this index, every spatial query falls back to a seq scan + per-row
-- ST_* function call. With it, even at 1M+ rows we stay in single-digit ms.
CREATE INDEX idx_restaurants_geom ON restaurants USING GIST (geom);


-- ── 4. Dedup fingerprint column ─────────────────────────────────────────────
--
-- Stored as sha1(lower(unaccent(name)) || '|' || round(lat,4) || '|' || round(lng,4))
-- — computed in RestaurantSyncService.upsert(). 4-decimal rounding ≈ 11 metres
-- precision, which is the right granularity for "this is the same physical
-- restaurant even if Goong gave it a slightly different place_id."
--
-- Indexed (non-unique) so the upsert path can probe it cheaply. We do NOT
-- promote it to UNIQUE because the seed data and existing rows do not have
-- it populated yet, and a future provider could legitimately produce two
-- restaurants at the same address (food court). The application layer makes
-- the dedup decision; the index just makes the lookup fast.
ALTER TABLE restaurants
    ADD COLUMN dedup_fingerprint VARCHAR(64);

CREATE INDEX idx_restaurants_dedup_fingerprint
    ON restaurants (dedup_fingerprint)
    WHERE dedup_fingerprint IS NOT NULL;


-- ── 5. place_cells: tile coverage ledger ────────────────────────────────────
--
-- Each row records the state of one geohash-6 cell. Geohash precision 6 yields
-- cells of roughly 1.2 km × 0.6 km at HCMC's latitude, which sits comfortably
-- inside a typical zoom-14/15 map viewport — i.e. one user-visible map covers
-- a handful of cells, not hundreds.
--
-- WHY VARCHAR PK AND NOT BIGSERIAL?
--   The cell_key (e.g. "wq6n7q") IS the natural primary key — it is globally
--   unique, deterministic, and embeds the location. Surrogate IDs would force
--   every read to indirect through a lookup. With cell_key as PK, the
--   DiscoveryService can compute the key from a viewport corner and probe
--   `WHERE cell_key IN (...)` directly.
--
-- COLUMNS
--   last_indexed_at  Drives the freshness check. The IngestionWorker updates
--                    this every time it successfully syncs the cell.
--   place_count      Diagnostic — how many places did Goong return for this
--                    cell? Lets us spot cells where Goong has poor coverage
--                    (could later trigger a wider-radius re-crawl).
--   center_lat/lng   Stored so the worker doesn't have to re-decode the
--                    geohash to call Goong's nearby endpoint.
--   source           SEED  : populated by the startup HCMC seed crawl
--                    VIEWPORT : on-demand from a user pan
--                    MANUAL : admin-triggered
--                    Useful for telemetry — "how much of our DB came from
--                    user-driven discovery vs. our own seed?"
-- =============================================================================
CREATE TABLE place_cells (
    cell_key        VARCHAR(12)      PRIMARY KEY,
    last_indexed_at TIMESTAMPTZ      NOT NULL,
    place_count     INTEGER          NOT NULL DEFAULT 0,
    center_lat      DOUBLE PRECISION NOT NULL,
    center_lng      DOUBLE PRECISION NOT NULL,
    source          VARCHAR(20)      NOT NULL DEFAULT 'VIEWPORT',

    CONSTRAINT chk_place_cells_source
        CHECK (source IN ('SEED', 'VIEWPORT', 'MANUAL'))
);

-- Drives the recrawl scheduler ("oldest cells first").
CREATE INDEX idx_place_cells_last_indexed_at ON place_cells (last_indexed_at);
