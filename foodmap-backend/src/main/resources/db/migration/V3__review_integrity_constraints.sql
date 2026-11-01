-- =============================================================================
-- V3 — Review integrity constraints and safety-net CHECK constraints
--
-- CHANGES:
--   1. Partial unique indexes on reviews — prevents duplicate review race condition
--   2. Non-negative guards on review_count and view_count
--   3. Non-negative guard on restaurant_foods.price
--   4. DB-level comment length cap on reviews.comment
-- =============================================================================


-- =============================================================================
-- 1. DUPLICATE REVIEW PREVENTION
--
-- Business rule: one general review per (user, restaurant),
--                one dish review per (user, restaurant, food).
--
-- WHY TWO PARTIAL INDEXES instead of one UNIQUE(user_id, restaurant_id, food_id):
--   PostgreSQL treats NULL as "unknown" in unique constraints.
--   Two rows with (user_id=1, restaurant_id=1, food_id=NULL) are considered
--   *distinct* under a standard UNIQUE constraint — the constraint is silently
--   bypassed whenever food_id IS NULL. Partial unique indexes solve this:
--   each index fires only in the domain it covers.
--
-- WHY DB CONSTRAINT IN ADDITION TO APPLICATION CHECK:
--   The service already checks for duplicates before INSERTing. That check is a
--   SELECT + INSERT — a TOCTOU (time-of-check time-of-use) window. Two concurrent
--   POST /reviews requests from the same user both pass the SELECT check before
--   either commits, and both INSERTs succeed. The partial unique index makes the
--   second INSERT fail at the database level with a unique_violation (23505),
--   regardless of concurrency. One layer of defence, two layers of safety.
-- =============================================================================

-- One general review per (user, restaurant) — enforced when food_id IS NULL.
CREATE UNIQUE INDEX uk_reviews_one_per_user_restaurant_general
    ON reviews (user_id, restaurant_id)
    WHERE food_id IS NULL;

-- One dish review per (user, restaurant, food) — enforced when food_id IS NOT NULL.
CREATE UNIQUE INDEX uk_reviews_one_per_user_restaurant_food
    ON reviews (user_id, restaurant_id, food_id)
    WHERE food_id IS NOT NULL;


-- =============================================================================
-- 2. NON-NEGATIVE COUNTERS ON RESTAURANTS
--
-- review_count and view_count are denormalized aggregates recomputed after every
-- review write. A bug in that logic (or a direct SQL UPDATE) could produce a
-- negative count, which is semantically impossible. These constraints are a
-- last-resort safety net — they cannot be expressed as Java type constraints
-- because Integer allows any value.
-- =============================================================================

ALTER TABLE restaurants
    ADD CONSTRAINT chk_restaurants_review_count_non_negative
        CHECK (review_count >= 0);

ALTER TABLE restaurants
    ADD CONSTRAINT chk_restaurants_view_count_non_negative
        CHECK (view_count >= 0);


-- =============================================================================
-- 3. NON-NEGATIVE PRICE ON RESTAURANT FOODS
--
-- price is optional (nullable) — not every restaurant publishes prices.
-- When present, a negative price has no business meaning.
-- =============================================================================

ALTER TABLE restaurant_foods
    ADD CONSTRAINT chk_restaurant_foods_price_non_negative
        CHECK (price IS NULL OR price >= 0);


-- =============================================================================
-- 4. COMMENT LENGTH CAP ON REVIEWS
--
-- CreateReviewRequest validates @Size(max = 2000) at the HTTP boundary.
-- This CHECK mirrors that rule at the DB layer so that any write path
-- (admin tooling, future batch imports, direct SQL) is also constrained.
-- LENGTH() in PostgreSQL counts characters, not bytes — correct for Unicode.
-- =============================================================================

ALTER TABLE reviews
    ADD CONSTRAINT chk_reviews_comment_length
        CHECK (comment IS NULL OR LENGTH(comment) <= 2000);
