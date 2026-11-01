package com.foodmap.restaurant.repository;

import com.foodmap.restaurant.domain.Restaurant;
import com.foodmap.restaurant.dto.AreaSupplyRow;
import com.foodmap.restaurant.dto.AvailabilityCandidateRow;
import com.foodmap.restaurant.dto.KnownPlaceRow;
import com.foodmap.restaurant.dto.PlaceMarkerProjection;
import com.foodmap.restaurant.dto.SearchCandidateRow;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface RestaurantRepository extends JpaRepository<Restaurant, Long> {

    Optional<Restaurant> findByGoongPlaceId(String goongPlaceId);

    /**
     * Secondary dedup probe: same physical restaurant under a different
     * Goong place_id. Used by RestaurantSyncService.upsert when the
     * primary place_id lookup misses.
     */
    Optional<Restaurant> findByDedupFingerprint(String dedupFingerprint);

    /** Snapshot ingestion: every stored restaurant's identity fields (read-only, one query). */
    @Query("""
        SELECT r.id AS id, r.goongPlaceId AS goongPlaceId, r.name AS name, r.address AS address,
               r.latitude AS latitude, r.longitude AS longitude
        FROM Restaurant r
        """)
    List<KnownPlaceRow> findKnownPlaces();

    /**
     * Load a restaurant and immediately acquire a row-level exclusive lock
     * (PostgreSQL: SELECT ... FOR UPDATE).
     *
     * Used by ReviewService before any review write operation. Without this lock,
     * two concurrent review submissions for the same restaurant can both read stale
     * denormalized stats, both compute an incorrect recalculation, and the last
     * write wins with wrong data. With this lock, the second transaction blocks
     * at this point until the first commits, guaranteeing the recalculation always
     * sees all previously committed reviews.
     *
     * Must be called inside an active @Transactional context — the lock is held
     * for the duration of the surrounding transaction.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT r FROM Restaurant r WHERE r.id = :id")
    Optional<Restaurant> findByIdForUpdate(@Param("id") Long id);

    boolean existsByGoongPlaceId(String goongPlaceId);

    // ── Viewport query (Phase 1: plain bounding box) ─────
    @Query("""
        SELECT r FROM Restaurant r
        WHERE r.isActive = true
          AND r.latitude  BETWEEN :swLat AND :neLat
          AND r.longitude BETWEEN :swLng AND :neLng
        ORDER BY r.avgRating DESC
        """)
    List<Restaurant> findInViewport(
        @Param("swLat") double swLat, @Param("swLng") double swLng,
        @Param("neLat") double neLat, @Param("neLng") double neLng,
        Pageable pageable
    );

    // ── Nearby bounding-box pre-filter (exact Haversine applied in service) ──
    @Query("""
        SELECT r FROM Restaurant r
        WHERE r.isActive = true
          AND r.latitude  BETWEEN :swLat AND :neLat
          AND r.longitude BETWEEN :swLng AND :neLng
        """)
    List<Restaurant> findInBoundingBox(
        @Param("swLat") double swLat, @Param("swLng") double swLng,
        @Param("neLat") double neLat, @Param("neLng") double neLng
    );

    // ── PostGIS envelope: viewport read (V5 spatial path) ────────────────────
    //
    // `r.geom && envelope` uses the GIST index on geom and is the right
    // operator for "bbox overlap." Equivalent in result to the JPQL
    // `BETWEEN` query above, but index-driven at any data size.
    //
    // `ORDER BY avg_rating DESC NULLS LAST` keeps newly-ingested restaurants
    // (avg_rating defaults to 0) below ones that already have reviews — a
    // small UX nicety once the ingestion path starts adding rows the seed
    // doesn't cover.
    @Query(value = """
        SELECT * FROM restaurants r
        WHERE r.is_active = true
          AND r.geom && ST_MakeEnvelope(:swLng, :swLat, :neLng, :neLat, 4326)
        ORDER BY r.avg_rating DESC NULLS LAST
        LIMIT :cap
        """, nativeQuery = true)
    List<Restaurant> findInEnvelope(
        @Param("swLat") double swLat, @Param("swLng") double swLng,
        @Param("neLat") double neLat, @Param("neLng") double neLng,
        @Param("cap")   int cap
    );

    // ── PostGIS radius + KNN: nearby read ────────────────────────────────────
    //
    // ST_DWithin with geography casts gives a true metres-on-the-ellipsoid
    // filter (no flat-earth approximation). The GIST index still serves the
    // call because PostGIS recognises geography-on-geometry as a wrapped
    // form of the same index.
    //
    // `ORDER BY r.geom <-> point` is the KNN operator — fast nearest-neighbour
    // walk over the GIST index, no per-row distance computation needed for
    // ordering.
    @Query(value = """
        SELECT * FROM restaurants r
        WHERE r.is_active = true
          AND ST_DWithin(
                  r.geom::geography,
                  ST_SetSRID(ST_MakePoint(:lng, :lat), 4326)::geography,
                  :radius
              )
        ORDER BY r.geom <-> ST_SetSRID(ST_MakePoint(:lng, :lat), 4326)
        LIMIT :cap
        """, nativeQuery = true)
    List<Restaurant> findWithinRadius(
        @Param("lat") double lat, @Param("lng") double lng,
        @Param("radius") double radiusMeters,
        @Param("cap") int cap
    );

    // ── PostGIS radius + KNN: decision candidates ("Ăn gì hôm nay?") ──────────
    //
    // Read-only candidate source for DecisionService. Differs from
    // findWithinRadius in two deliberate ways:
    //  1. Casts are written CAST(... AS geography). Hibernate 6 rewrites the
    //     PostgreSQL `::` cast inside native queries (it reaches the driver as
    //     a single ':'), so the `::geography` form fails at runtime.
    //  2. `r.geom && envelope` is an index-driven pre-filter on the geometry
    //     GIST index (partial idx_restaurants_geom_active). The geography
    //     ST_DWithin that follows is the exact metres check — on its own it
    //     cannot use a geometry index. The caller pads the envelope so it never
    //     clips a row the exact check would keep.
    @Query(value = """
        SELECT * FROM restaurants r
        WHERE r.is_active = true
          AND r.geom && ST_MakeEnvelope(:swLng, :swLat, :neLng, :neLat, 4326)
          AND ST_DWithin(
                  CAST(r.geom AS geography),
                  CAST(ST_SetSRID(ST_MakePoint(:lng, :lat), 4326) AS geography),
                  :radius
              )
        ORDER BY r.geom <-> ST_SetSRID(ST_MakePoint(:lng, :lat), 4326)
        LIMIT :cap
        """, nativeQuery = true)
    List<Restaurant> findDecisionCandidates(
        @Param("lat") double lat, @Param("lng") double lng,
        @Param("radius") double radiusMeters,
        @Param("swLat") double swLat, @Param("swLng") double swLng,
        @Param("neLat") double neLat, @Param("neLng") double neLng,
        @Param("cap") int cap
    );

    // ── Food availability: every active restaurant inside a radius ───────────
    //
    // Same spatial predicate as findDecisionCandidates (index bbox pre-filter
    // + exact geography ST_DWithin, CAST form for Hibernate 6) but a light
    // projection with the exact distance, so one query at the widest radius
    // also tells which smaller radius each row falls in. :cap is a safety
    // bound far above current data; callers report when it was hit.
    @Query(value = """
        SELECT r.id           AS "id",
               r.name         AS "name",
               r.cuisine_type AS "cuisineType",
               r.latitude     AS "latitude",
               r.longitude    AS "longitude",
               ST_Distance(
                   CAST(r.geom AS geography),
                   CAST(ST_SetSRID(ST_MakePoint(:lng, :lat), 4326) AS geography)
               ) AS "distanceMeters"
        FROM restaurants r
        WHERE r.is_active = true
          AND r.geom && ST_MakeEnvelope(:swLng, :swLat, :neLng, :neLat, 4326)
          AND ST_DWithin(
                  CAST(r.geom AS geography),
                  CAST(ST_SetSRID(ST_MakePoint(:lng, :lat), 4326) AS geography),
                  :radius
              )
        ORDER BY r.id
        LIMIT :cap
        """, nativeQuery = true)
    List<AvailabilityCandidateRow> findAvailabilityCandidates(
        @Param("lat") double lat, @Param("lng") double lng,
        @Param("radius") double radiusMeters,
        @Param("swLat") double swLat, @Param("swLng") double swLng,
        @Param("neLat") double neLat, @Param("neLng") double neLng,
        @Param("cap") int cap
    );

    // ── Discovery-area supply: restaurants per area, all areas in ONE query ──
    //
    // Areas arrive as three parallel comma-separated lists (centre lat, centre
    // lng, radius in metres); row i of the result is area i (1-based). Same
    // spatial predicate as findAvailabilityCandidates (exact geography
    // ST_DWithin) and the same minimum as the decision eligibility check: a
    // name and coordinates. Read-only.
    @Query(value = """
        SELECT a.idx       AS "areaIndex",
               COUNT(r.id) AS "restaurantCount"
        FROM unnest(
                 CAST(string_to_array(:lats, ',') AS double precision[]),
                 CAST(string_to_array(:lngs, ',') AS double precision[]),
                 CAST(string_to_array(:radii, ',') AS double precision[])
             ) WITH ORDINALITY AS a(lat, lng, radius, idx)
        LEFT JOIN restaurants r
               ON r.is_active = true
              AND r.name IS NOT NULL AND btrim(r.name) <> ''
              AND r.latitude IS NOT NULL AND r.longitude IS NOT NULL
              AND ST_DWithin(
                      CAST(r.geom AS geography),
                      CAST(ST_SetSRID(ST_MakePoint(a.lng, a.lat), 4326) AS geography),
                      a.radius
                  )
        GROUP BY a.idx
        ORDER BY a.idx
        """, nativeQuery = true)
    List<AreaSupplyRow> countRestaurantsWithinAreas(
        @Param("lats") String lats, @Param("lngs") String lngs, @Param("radii") String radiiMeters
    );

    // ── Restaurants serving a specific food ─────────────────────────────────
    @Query("""
        SELECT r FROM Restaurant r
        JOIN r.restaurantFoods rf
        WHERE rf.food.id = :foodId
          AND r.isActive = true
        ORDER BY r.avgRating DESC
        """)
    Page<Restaurant> findByFoodId(@Param("foodId") Long foodId, Pageable pageable);

    // ── Restaurant search, stage 1: bounded candidate retrieval ──────────────
    //
    // This query only gathers a superset of plausible hits; relevance is decided
    // in Java (restaurant.search.SearchRanker), which applies the accent rules
    // PostgreSQL's unaccent cannot express ("phở" ≠ "phố"). Everything here is
    // folded, so it over-matches on purpose and never under-matches:
    //   - folded name contains the query       (LIKE, idx_restaurants_name_trgm)
    //   - folded name is trigram-similar       (%,    idx_restaurants_name_trgm)
    //   - every query word in name + address   (FTS,  idx_restaurants_search_fts)
    //   - a FoodMap menu dish contains it      (restaurant_foods → foods)
    // Ordering only decides which rows survive the cap: names starting with the
    // query first, then nearest (user origin only) or lowest id.
    //
    // :likeAny / :likePrefix are '%q%' / 'q%' built by the caller with LIKE
    // wildcards escaped. plainto_tsquery keeps user operators out of FTS.
    String SEARCH_CANDIDATE_SELECT = """
        SELECT r.id           AS "id",
               r.name         AS "name",
               r.address      AS "address",
               r.avg_rating   AS "avgRating",
               r.review_count AS "reviewCount",
               r.latitude     AS "latitude",
               r.longitude    AS "longitude",
               CAST(similarity(immutable_unaccent(lower(r.name)), immutable_unaccent(lower(:query)))
                    AS DOUBLE PRECISION) AS "nameSimilarity"
        FROM restaurants r
        WHERE r.is_active = true
          AND (
                immutable_unaccent(lower(r.name)) LIKE immutable_unaccent(lower(:likeAny)) ESCAPE '\\'
             OR immutable_unaccent(lower(r.name)) % immutable_unaccent(lower(:query))
             OR to_tsvector('simple', immutable_unaccent(coalesce(r.name, '') || ' ' || coalesce(r.address, '')))
                    @@ plainto_tsquery('simple', immutable_unaccent(:query))
             OR r.id IN (
                    SELECT rf.restaurant_id
                    FROM restaurant_foods rf JOIN foods f ON f.id = rf.food_id
                    WHERE immutable_unaccent(lower(f.name)) LIKE immutable_unaccent(lower(:likeAny)) ESCAPE '\\'
                 )
          )
        ORDER BY (immutable_unaccent(lower(r.name)) LIKE immutable_unaccent(lower(:likePrefix)) ESCAPE '\\') DESC,
        """;

    @Query(value = SEARCH_CANDIDATE_SELECT + " r.id LIMIT :cap", nativeQuery = true)
    List<SearchCandidateRow> findSearchCandidates(
        @Param("query") String query,
        @Param("likeAny") String likeAny,
        @Param("likePrefix") String likePrefix,
        @Param("cap") int cap
    );

    /**
     * Stage-1 supplement for a query that names a catalog food: rows whose
     * folded name, or a FoodMap menu dish, contains any of the food's keywords
     * (synonyms the literal query would miss: "cafe" → "cà phê", "coffee").
     * {@code :patterns} is '%kw1%|%kw2%|…' (folded, no LIKE metacharacters —
     * catalog keywords are letters and spaces). Java decides what really matches.
     */
    @Query(value = """
        SELECT r.id           AS "id",
               r.name         AS "name",
               r.address      AS "address",
               r.avg_rating   AS "avgRating",
               r.review_count AS "reviewCount",
               r.latitude     AS "latitude",
               r.longitude    AS "longitude",
               CAST(similarity(immutable_unaccent(lower(r.name)), immutable_unaccent(lower(:query)))
                    AS DOUBLE PRECISION) AS "nameSimilarity"
        FROM restaurants r
        WHERE r.is_active = true
          AND (
                immutable_unaccent(lower(r.name)) LIKE ANY (string_to_array(:patterns, '|'))
             OR r.id IN (
                    SELECT rf.restaurant_id
                    FROM restaurant_foods rf JOIN foods f ON f.id = rf.food_id
                    WHERE immutable_unaccent(lower(f.name)) LIKE ANY (string_to_array(:patterns, '|'))
                 )
          )
        ORDER BY r.id
        LIMIT :cap
        """, nativeQuery = true)
    List<SearchCandidateRow> findFoodSearchCandidates(
        @Param("query") String query,
        @Param("patterns") String patterns,
        @Param("cap") int cap
    );

    /** Same as {@link #findSearchCandidates}, keeping the nearest rows when the cap applies. */
    @Query(value = SEARCH_CANDIDATE_SELECT
        + " r.geom <-> ST_SetSRID(ST_MakePoint(:lng, :lat), 4326), r.id LIMIT :cap", nativeQuery = true)
    List<SearchCandidateRow> findSearchCandidatesNear(
        @Param("query") String query,
        @Param("likeAny") String likeAny,
        @Param("likePrefix") String likePrefix,
        @Param("lat") double lat,
        @Param("lng") double lng,
        @Param("cap") int cap
    );

    // ── Local geo-search (/api/v1/places/search) ────────────────────────────
    //
    // WHY local-only:
    //   This endpoint is part of the move away from vendor (Goong) realtime
    //   search. Goong has no Nearby Search API and even its AutoComplete is
    //   too slow / rate-limited / expensive to put in the request path.
    //   Goong is now ingestion-only (async worker writes into restaurants);
    //   every read here is served from PostGIS + GIN, never round-tripped.
    //
    // QUERY SHAPE:
    //   One parameterised query handles three modes:
    //     1. bbox     — geom && ST_MakeEnvelope(...)          → GIST scan
    //     2. radius   — ST_DWithin(geom::geography, ...)      → GIST + recheck
    //     3. nothing  — text/category only                    → GIN + filter
    //   CAST(:param AS ...) IS NULL is the standard idiom for "skip this
    //   predicate when the bind is null" — Postgres elides the disjunct
    //   when it can prove the parameter is null at plan time.
    //
    // RANKING:
    //   distance > popularity > recency, weighted 0.6 / 0.3 / 0.1.
    //     - distance:    1 / (1 + meters/1000)        → 1.0 at 0m, ~0.5 at 1km
    //     - popularity: (rating/5) * (ln(1+reviews)/ln(101))
    //                                                 → log-damped review count
    //     - recency:    1 - days_since_sync / 365     → clamped to [0, 1]
    //   ST_Distance is computed once per row in the inner subquery so the
    //   outer ranking expression reuses the value (no double evaluation).
    //
    // PERFORMANCE NOTES (see EXPLAIN expectations in PlaceSearchService docs):
    //   - is_active = true is a hot-path filter; idx_restaurants_geom_active
    //     and idx_restaurants_popularity are partial on this predicate.
    //   - The text predicate uses BitmapOr over FTS GIN + trigram GIN; the
    //     spatial predicate is BitmapAnd-ed in when both are present.
    //   - LIMIT/OFFSET pagination is fine for the small page sizes a map
    //     UI actually requests (≤ 100). For deep paging, switch to keyset.
    @Query(
        value = """
            SELECT id              AS "id",
                   name            AS "name",
                   latitude        AS "latitude",
                   longitude       AS "longitude",
                   avg_rating      AS "avgRating",
                   review_count    AS "reviewCount",
                   price_level     AS "priceLevel",
                   cuisine_type    AS "cuisineType",
                   dist_m          AS "distanceMeters"
            FROM (
                SELECT r.id, r.name, r.latitude, r.longitude,
                       r.avg_rating, r.review_count, r.price_level,
                       r.cuisine_type, r.last_synced_at,
                       ST_Distance(
                           r.geom::geography,
                           ST_SetSRID(ST_MakePoint(:centerLng, :centerLat), 4326)::geography
                       ) AS dist_m
                FROM restaurants r
                WHERE r.is_active = true
                  AND (CAST(:swLng AS DOUBLE PRECISION) IS NULL
                       OR r.geom && ST_MakeEnvelope(:swLng, :swLat, :neLng, :neLat, 4326))
                  AND (CAST(:radiusMeters AS DOUBLE PRECISION) IS NULL
                       OR ST_DWithin(
                              r.geom::geography,
                              ST_SetSRID(ST_MakePoint(:centerLng, :centerLat), 4326)::geography,
                              CAST(:radiusMeters AS DOUBLE PRECISION)
                          ))
                  AND (CAST(:category AS TEXT) IS NULL
                       OR lower(r.cuisine_type) = lower(CAST(:category AS TEXT)))
                  AND (CAST(:keyword AS TEXT) IS NULL
                       OR to_tsvector('simple', immutable_unaccent(coalesce(r.name, '') || ' ' || coalesce(r.address, '')))
                              @@ plainto_tsquery('simple', immutable_unaccent(CAST(:keyword AS TEXT)))
                       OR immutable_unaccent(lower(r.name))
                              % immutable_unaccent(lower(CAST(:keyword AS TEXT))))
            ) AS inner_q
            ORDER BY (
                0.6 * (1.0 / (1.0 + dist_m / 1000.0))
              + 0.3 * (COALESCE(avg_rating, 0)::FLOAT / 5.0)
                    * (LN(1 + review_count) / LN(101))
              + 0.1 * GREATEST(0.0, 1.0 - EXTRACT(EPOCH FROM (NOW() - COALESCE(last_synced_at, NOW()))) / (365 * 86400))
            ) DESC
            """,
        countQuery = """
            SELECT count(*)
            FROM restaurants r
            WHERE r.is_active = true
              AND (CAST(:swLng AS DOUBLE PRECISION) IS NULL
                   OR r.geom && ST_MakeEnvelope(:swLng, :swLat, :neLng, :neLat, 4326))
              AND (CAST(:radiusMeters AS DOUBLE PRECISION) IS NULL
                   OR ST_DWithin(
                          r.geom::geography,
                          ST_SetSRID(ST_MakePoint(:centerLng, :centerLat), 4326)::geography,
                          CAST(:radiusMeters AS DOUBLE PRECISION)
                      ))
              AND (CAST(:category AS TEXT) IS NULL
                   OR lower(r.cuisine_type) = lower(CAST(:category AS TEXT)))
              AND (CAST(:keyword AS TEXT) IS NULL
                   OR to_tsvector('simple', immutable_unaccent(coalesce(r.name, '') || ' ' || coalesce(r.address, '')))
                          @@ plainto_tsquery('simple', immutable_unaccent(CAST(:keyword AS TEXT)))
                   OR immutable_unaccent(lower(r.name))
                          % immutable_unaccent(lower(CAST(:keyword AS TEXT))))
            """,
        nativeQuery = true
    )
    Page<PlaceMarkerProjection> searchPlaces(
        @Param("centerLat")     double centerLat,
        @Param("centerLng")     double centerLng,
        @Param("swLat")         Double swLat,
        @Param("swLng")         Double swLng,
        @Param("neLat")         Double neLat,
        @Param("neLng")         Double neLng,
        @Param("radiusMeters")  Double radiusMeters,
        @Param("category")      String category,
        @Param("keyword")       String keyword,
        Pageable pageable
    );

    // ── Denormalized aggregate update (called after each review write) ───────
    @Modifying
    @Query("""
        UPDATE Restaurant r
        SET r.avgRating   = (SELECT COALESCE(AVG(CAST(rv.rating AS double)), 0) FROM Review rv WHERE rv.restaurant.id = r.id),
            r.reviewCount = (SELECT COUNT(rv) FROM Review rv WHERE rv.restaurant.id = r.id)
        WHERE r.id = :restaurantId
        """)
    void recalculateRatingStats(@Param("restaurantId") Long restaurantId);
}
