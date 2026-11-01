-- =============================================================================
-- V4 — Replace LIKE '%...%' search with PostgreSQL FTS + trigram
--
-- WHY THIS MIGRATION EXISTS
--   The previous searchByName / searchByNameOrAddress queries used
--   LOWER(col) LIKE LOWER('%term%'). A leading wildcard prevents any B-tree
--   from helping — the planner falls back to a sequential scan + LOWER() per
--   row + pattern match per row. Cost grows linearly with the table size and
--   the SearchBar fires this query per debounced keystroke. This migration
--   gives the planner real indexes to use.
--
-- WHAT IT ADDS
--   1. pg_trgm  — trigram operators and similarity() function, lets a GIN
--                 index serve ILIKE/% (similarity) queries in milliseconds.
--   2. unaccent — strips diacritics ("phở" → "pho") so accent-insensitive
--                 search works without normalising data at write time.
--   3. immutable_unaccent() — unaccent() ships as STABLE because it reads a
--                 config file. Index expressions require IMMUTABLE functions,
--                 so we wrap it. Safe as long as nobody edits unaccent.rules
--                 at runtime — a fair assumption for this project.
--   4. Functional GIN indexes for trigram and FTS lookups.
--
-- PERMISSIONS NOTE
--   CREATE EXTENSION requires a role with rds_superuser / superuser.
--   On managed Postgres (Supabase / Neon / RDS) both extensions are usually
--   pre-installed; if not, ask the DBA to run them once.
-- =============================================================================


-- ── 1. Extensions ───────────────────────────────────────────────────────────
CREATE EXTENSION IF NOT EXISTS pg_trgm;
CREATE EXTENSION IF NOT EXISTS unaccent;


-- ── 2. IMMUTABLE wrapper around unaccent() ──────────────────────────────────
--
-- The contrib unaccent() is declared STABLE, not IMMUTABLE, because it reads
-- the unaccent.rules dictionary at call time. PostgreSQL refuses to build an
-- index on a STABLE expression — the index value could change without the
-- index knowing. We assert IMMUTABLE here by contract: as long as the rules
-- file is not edited after indexes are built, this is correct. This is a
-- well-known idiom (referenced in the official pg_trgm docs).
CREATE OR REPLACE FUNCTION immutable_unaccent(text)
RETURNS text
LANGUAGE sql
IMMUTABLE
PARALLEL SAFE
STRICT
AS $$
    SELECT unaccent('unaccent', $1)
$$;


-- ── 3. Trigram GIN index on foods.name ──────────────────────────────────────
--
-- gin_trgm_ops tells GIN to break the value into 3-character substrings
-- (trigrams). A query like  immutable_unaccent(lower(name)) % :q  becomes a
-- bitmap scan over the trigrams of :q. The actual rows scanned are usually
-- a tiny fraction of the table — orders of magnitude faster than a seq scan.
--
-- The expression here (immutable_unaccent(lower(name))) MUST match the exact
-- expression used in the WHERE clause of FoodRepository.searchByName, or the
-- planner will not use the index.
CREATE INDEX idx_foods_name_trgm
    ON foods
    USING GIN (immutable_unaccent(lower(name)) gin_trgm_ops);


-- ── 4. Trigram GIN index on restaurants.name ────────────────────────────────
--
-- Same idea as above. We index name only (not address) for the trigram path
-- because dish/restaurant *names* are short fragments where typo tolerance
-- matters most. Addresses are word-shaped; FTS (below) serves them better.
CREATE INDEX idx_restaurants_name_trgm
    ON restaurants
    USING GIN (immutable_unaccent(lower(name)) gin_trgm_ops);


-- ── 5. FTS GIN index on restaurants (name + address) ────────────────────────
--
-- to_tsvector tokenises the text into lexemes. We use the 'simple' config:
--   - lowercases
--   - splits on whitespace/punctuation
--   - does NOT stem and does NOT remove stopwords
-- 'simple' is the right pick for Vietnamese — there is no built-in Vietnamese
-- dictionary, and 'english' would mangle Vietnamese tokens. 'simple' is
-- predictable, which is what you want for autocomplete.
--
-- We concatenate name + address with coalesce() so a NULL address does not
-- nuke the whole tsvector. Two literal string constants (' ', '') keep the
-- expression IMMUTABLE.
--
-- This index supports queries of the form:
--   to_tsvector(...) @@ plainto_tsquery('simple', immutable_unaccent(:q))
CREATE INDEX idx_restaurants_search_fts
    ON restaurants
    USING GIN (
        to_tsvector(
            'simple',
            immutable_unaccent(coalesce(name, '') || ' ' || coalesce(address, ''))
        )
    );
