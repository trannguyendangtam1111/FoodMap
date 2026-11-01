-- =====================================================================
-- V7 — Per-cell retry state + cooldown gate for the viewport enrichment
--      workflow.
--
-- Background:
--   V5 introduced place_cells as a binary "have we touched this cell?"
--   ledger gated by last_indexed_at + a hard-coded 30-day freshness.
--   That meant a cell that *failed* to ingest looked indistinguishable
--   from a cell that succeeded — so failures got a free 30 days of
--   silence and low-density cells (zero/few places) waited just as long
--   as fully-populated ones.
--
-- This migration replaces the single freshness gate with a richer model:
--   - next_eligible_at  — explicit cooldown timestamp. The discovery read
--                         path now asks "when is this cell next eligible?"
--                         instead of "how long since last index?". Lets
--                         the worker pick the cooldown that matches the
--                         outcome (fresh → 30d, low-density → 6h,
--                         transient failure → exponential backoff,
--                         permanent failure → 24h).
--   - status            — READY (last attempt succeeded, or transient
--                         retry pending) or FAILED (gave up after the
--                         retry budget). Telemetry-only.
--   - attempt_count     — reset to 0 on success; incremented per failure
--                         to drive exponential backoff.
--   - last_attempted_at — distinct from last_indexed_at: this is set on
--                         every attempt, success OR failure.
--   - last_error        — short reason string for the last failure
--                         (e.g. "fanout_all_failed"). Kept short on
--                         purpose so it stays scannable in psql.
-- =====================================================================

ALTER TABLE place_cells
    ADD COLUMN IF NOT EXISTS status            VARCHAR(16)  NOT NULL DEFAULT 'READY',
    ADD COLUMN IF NOT EXISTS attempt_count     INTEGER      NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS last_attempted_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS next_eligible_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    ADD COLUMN IF NOT EXISTS last_error        VARCHAR(255);

ALTER TABLE place_cells
    DROP CONSTRAINT IF EXISTS chk_place_cells_status;
ALTER TABLE place_cells
    ADD CONSTRAINT chk_place_cells_status
        CHECK (status IN ('READY', 'FAILED'));

-- Backfill: existing rows are all "successful" (the old recordCoverage
-- did not distinguish). Treat them as if they had a 30-day cooldown
-- starting from their last_indexed_at — preserves the previous staleness
-- behaviour exactly.
UPDATE place_cells
   SET next_eligible_at = last_indexed_at + INTERVAL '30 days'
 WHERE last_indexed_at IS NOT NULL;

-- ---------------------------------------------------------------------
-- Index for the discovery hot path. Every viewport read calls
-- staleOrUnseen(), which queries WHERE cell_key IN (...) AND
-- next_eligible_at > now(). Btree on next_eligible_at lets the planner
-- combine the PK probe (cell_key IN) with a range scan on the cooldown
-- predicate.
-- ---------------------------------------------------------------------
CREATE INDEX IF NOT EXISTS idx_place_cells_next_eligible_at
    ON place_cells (next_eligible_at);

-- ---------------------------------------------------------------------
-- Partial index over "interesting" cells for ops queries (permanently
-- failed or low-density). Kept partial so it stays tiny.
-- ---------------------------------------------------------------------
CREATE INDEX IF NOT EXISTS idx_place_cells_attention
    ON place_cells (status, place_count)
    WHERE status = 'FAILED' OR place_count < 3;
