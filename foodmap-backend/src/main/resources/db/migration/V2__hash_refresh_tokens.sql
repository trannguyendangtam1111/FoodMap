-- =============================================================================
-- V2 — Secure refresh token storage
--
-- WHAT CHANGES:
--   1. All existing refresh tokens are deleted.
--      They were stored as raw JWTs. The new code stores SHA-256 hashes.
--      The two formats are incompatible — the new code cannot validate old tokens.
--      All active sessions are invalidated; users must log in again after deploy.
--      This is the expected and correct behaviour when changing a security-critical
--      storage format.
--
--   2. token column: VARCHAR(1024) → VARCHAR(64)
--      SHA-256 hex output is exactly 64 characters, always.
--      Shrinking the column documents this invariant at the schema level.
--
--   3. version column added for optimistic locking.
--      JPA @Version on the RefreshToken entity; Hibernate manages the counter.
--      Prevents a race condition where two concurrent refresh requests both
--      succeed with the same token (double-spend). The second write fails
--      with a version mismatch, returning 401 to that request.
-- =============================================================================

-- Step 1: Invalidate all existing sessions.
-- Old tokens are stored as raw JWTs and are not compatible with the new
-- SHA-256 hashed storage format. Purging is safe here — users just need
-- to log in again after this deployment.
DELETE FROM refresh_tokens;

-- Step 2: Shrink the token column to exactly match SHA-256 hex output.
-- The table is empty so there is no data to truncate or convert.
ALTER TABLE refresh_tokens
    ALTER COLUMN token TYPE VARCHAR(64);

-- Step 3: Add the optimistic lock column.
-- DEFAULT 0 matches Hibernate's initial value for new entities.
-- NOT NULL: every row must have a version tracked.
ALTER TABLE refresh_tokens
    ADD COLUMN version BIGINT NOT NULL DEFAULT 0;
