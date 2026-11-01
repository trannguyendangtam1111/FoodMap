-- =============================================================================
-- V7 — Expand VARCHAR columns in restaurants that can overflow
--
-- CHANGES
--   1. goong_place_id  VARCHAR(128) → VARCHAR(512)
--        Goong place IDs are opaque strings with no documented length cap.
--        IDs observed in production have exceeded 128 chars.
--
--   2. website         VARCHAR(500) → TEXT
--        Goong can return proxied, redirect-wrapped, or UTM-decorated URLs
--        that exceed 500 chars. TEXT removes the cap entirely.
--
-- NOTES
--   - provider_raw is already TEXT in V1 — no change needed.
--   - Increasing a VARCHAR limit and VARCHAR→TEXT are both metadata-only
--     operations in PostgreSQL: no table rewrite, no lock beyond ACCESS EXCLUSIVE
--     for the catalog update (held for milliseconds, safe on live tables).
--   - The unique index on goong_place_id does not need to be recreated —
--     Postgres rebuilds index metadata in-place for the type change.
-- =============================================================================

ALTER TABLE restaurants ALTER COLUMN goong_place_id TYPE VARCHAR(512);
ALTER TABLE restaurants ALTER COLUMN website        TYPE TEXT;
