-- =====================================================================
-- V6 — Indexes for the local geo-search endpoint (/places/search).
--
-- Why this migration exists:
--   We are transitioning from vendor (Goong) realtime search to a
--   local-first PostGIS search. The new /places/search endpoint MUST
--   serve every read from Postgres, never from Goong. Goong is now
--   ingestion-only (async background worker; see KeywordFanoutService).
--   These indexes make the local read path fast enough that we never
--   need to round-trip to a vendor again.
--
-- Conventions:
--   - All indexes are partial WHERE is_active = true. We never serve
--     soft-deleted rows from the search endpoint, and the partial
--     index keeps each scan smaller and the planner stats sharper.
--   - We use CREATE INDEX IF NOT EXISTS so re-running the migration
--     against a partially-applied environment is safe (Flyway already
--     guards this at the meta level, but defence in depth is cheap).
-- =====================================================================

-- ---------------------------------------------------------------------
-- 1) Active-only GIST on geom.
--
-- The base GIST on `geom` (from V5) already serves && and ST_DWithin.
-- The partial variant is materially smaller (inactive rows skipped)
-- and lets the planner avoid touching tombstoned rows on the hot path.
-- Used by EVERY /places/search request that supplies a bbox or radius.
-- ---------------------------------------------------------------------
CREATE INDEX IF NOT EXISTS idx_restaurants_geom_active
    ON restaurants
    USING GIST (geom)
    WHERE is_active = true;

-- ---------------------------------------------------------------------
-- 2) Case-insensitive cuisine_type lookup.
--
-- Category filter compares lower(cuisine_type) = lower(:category)
-- (case-insensitive exact match). A functional btree on the lowered
-- column matches the predicate exactly so the planner can pick this
-- index up directly. Partial where cuisine_type IS NOT NULL keeps the
-- index lean — restaurants without a known cuisine never appear in
-- category-filtered searches anyway.
-- ---------------------------------------------------------------------
CREATE INDEX IF NOT EXISTS idx_restaurants_cuisine_type_lower
    ON restaurants (lower(cuisine_type))
    WHERE cuisine_type IS NOT NULL;

-- ---------------------------------------------------------------------
-- 3) Popularity-ordered btree, active-only.
--
-- Backstop for the non-spatial fallback path (no bbox, no center —
-- e.g. "show me the best phở places anywhere"). Lets the planner walk
-- ranked rows in index order instead of materialising a Sort node.
-- NULLS LAST matches our COALESCE(avg_rating, 0) ranking convention.
-- ---------------------------------------------------------------------
CREATE INDEX IF NOT EXISTS idx_restaurants_popularity
    ON restaurants (avg_rating DESC NULLS LAST, review_count DESC)
    WHERE is_active = true;

-- =====================================================================
-- Indexes already present (do NOT recreate — listed for reference):
--   V4: idx_restaurants_name_trgm     — GIN trigram on unaccent(lower(name))
--   V4: idx_restaurants_search_fts    — GIN tsvector on name+address
--   V5: idx_restaurants_geom          — GIST on geom (general-purpose)
--   V1: idx_restaurants_cuisine_type  — btree on cuisine_type
--   V1: idx_restaurants_avg_rating    — btree on avg_rating
--   V1: idx_restaurants_is_active     — btree on is_active
-- =====================================================================
