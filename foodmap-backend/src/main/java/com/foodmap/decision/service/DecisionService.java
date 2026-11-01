package com.foodmap.decision.service;

import com.foodmap.common.util.GeoUtils;
import com.foodmap.config.properties.DecisionProperties;
import com.foodmap.decision.dto.AvailableFoodsResponse.AreaRef;
import com.foodmap.decision.dto.DecisionReason;
import com.foodmap.decision.dto.DecisionResponse;
import com.foodmap.decision.scope.LocationScope;
import com.foodmap.decision.service.MoodMatcher.Evidence;
import com.foodmap.restaurant.domain.Restaurant;
import com.foodmap.restaurant.dto.RestaurantDishNameProjection;
import com.foodmap.restaurant.mapper.RestaurantMapper;
import com.foodmap.restaurant.ranking.PersonalizationContext;
import com.foodmap.restaurant.ranking.RankingService;
import com.foodmap.restaurant.repository.RestaurantFoodRepository;
import com.foodmap.restaurant.repository.RestaurantRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * "Ăn gì hôm nay?" — the server picks one restaurant.
 *
 * Pipeline per stage:
 *   PostGIS radius query (RestaurantRepository#findDecisionCandidates: index
 *   bbox pre-filter + exact ST_DWithin, nearest first, capped)
 *   → drop excluded IDs and rows without a usable name/coordinates
 *   → optional mood filter (name or FoodMap menu, see {@link MoodMatcher})
 *   → existing {@link RankingService} order
 *   → weighted draw among the top-N ({@link DecisionSelector})
 *   → factual reasons.
 *
 * Stages: the scope's radius ladder (decision.scope.RadiusLadder), in order,
 * until one has candidates:
 *   USER:         requested radius  →  widened radius (× widenFactor, ≤ max)
 *   AREA:         the area's radius only (never widened, never a distance)
 *   CITY_CENTER:  3 km → 5 km around the city centre (never "near you")
 * A requested mood is relaxed before the radius is widened.
 *
 * READ-ONLY: this class only reads restaurants and menus. It has no
 * dependency on ingestion, Goong or any writer, and runs in a read-only
 * transaction, so the database itself rejects any accidental write.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DecisionService {

    public static final int LEVEL_EXACT          = 0;
    public static final int LEVEL_MOOD_RELAXED   = 1;
    public static final int LEVEL_RADIUS_WIDENED = 2;
    public static final int LEVEL_CITY_CENTER    = 3;

    public static final String SOURCE_USER        = LocationScope.SOURCE_USER;
    public static final String SOURCE_AREA        = LocationScope.SOURCE_AREA;
    public static final String SOURCE_CITY_CENTER = LocationScope.SOURCE_CITY_CENTER;

    /**
     * PostGIS measures on the ellipsoid, GeoUtils on a sphere; the two can
     * differ by a fraction of a percent. The Java re-check uses this slack so
     * it never drops a row PostGIS legitimately returned.
     */
    static final double RADIUS_TOLERANCE = 1.01;

    private static final Locale VI = Locale.forLanguageTag("vi-VN");

    private final RestaurantRepository restaurantRepository;
    private final RestaurantFoodRepository restaurantFoodRepository;
    private final RankingService rankingService;
    private final RestaurantMapper restaurantMapper;
    private final DecisionSelector selector;
    private final DecisionProperties props;

    @Transactional(readOnly = true)
    public DecisionResponse decide(DecisionRequest req) {
        LocationScope scope = req.scope();
        List<Integer> stages = scope.ladder().stages();

        for (int stage = 0; stage < stages.size(); stage++) {
            int radius = stages.get(stage);
            int level = levelOf(scope, stage);
            List<Restaurant> candidates = loadCandidates(scope.originLat(), scope.originLng(), radius, req.excludeIds());
            if (candidates.isEmpty()) continue;

            if (req.mood() == null) {
                return choose(req, radius, level, stage > 0, candidates, Map.of(), null);
            }

            Map<Long, Evidence> evidence = moodEvidence(req.mood(), candidates);
            List<Restaurant> fitting = candidates.stream().filter(r -> evidence.containsKey(r.getId())).toList();
            if (!fitting.isEmpty()) {
                return choose(req, radius, level, stage > 0, fitting, evidence, true);
            }
            // Nothing here fits the mood: relax the mood before widening the radius.
            int relaxed = level == LEVEL_EXACT ? LEVEL_MOOD_RELAXED : level;
            return choose(req, radius, relaxed, stage > 0, candidates, Map.of(), false);
        }

        int last = scope.ladder().widestStage();
        log.debug("decision: no candidates (scope={} radius={} mood={} excluded={})",
            scope, stages.get(last), slug(req), req.excludeIds().size());
        return new DecisionResponse(null, null, null, List.of(), 0, levelOf(scope, last),
            scope.source(), stages.get(last), slug(req), null,
            null, areaRef(scope), last > 0, DecisionResponse.NO_RESTAURANT_IN_SCOPE);
    }

    /**
     * fallbackLevel of a ladder stage: USER 0 (requested radius) or 2 (widened);
     * AREA 0 (the area the user chose); CITY_CENTER 3 (no location sent).
     */
    public static int levelOf(LocationScope scope, int stage) {
        if (scope instanceof LocationScope.CityCenter) return LEVEL_CITY_CENTER;
        if (scope instanceof LocationScope.Area) return LEVEL_EXACT;
        return stage == 0 ? LEVEL_EXACT : LEVEL_RADIUS_WIDENED;
    }

    /** The public reference of an AREA scope (key and label only); null otherwise. */
    public static AreaRef areaRef(LocationScope scope) {
        return scope.area() == null ? null : new AreaRef(scope.area().key(), scope.area().label());
    }

    /** Real distance from the user, rounded to metres — USER scopes only, otherwise null. */
    public static Integer distanceFromUser(LocationScope scope, Restaurant r) {
        return scope.showsDistance()
            ? (int) Math.round(GeoUtils.distanceMeters(scope.originLat(), scope.originLng(), r.getLatitude(), r.getLongitude()))
            : null;
    }

    /** First reason: "Cách bạn 450 m" for a USER scope, otherwise the scope's phrase ("Quanh Quận 1"). */
    public static DecisionReason whereReason(LocationScope scope, Integer distanceMeters) {
        return distanceMeters != null
            ? new DecisionReason("DISTANCE", "Cách bạn " + formatDistance(distanceMeters))
            : new DecisionReason("AREA", scope.label());
    }

    // ── Candidates ────────────────────────────────────────────────────────────

    private List<Restaurant> loadCandidates(double lat, double lng, int radiusMeters, Set<Long> excludeIds) {
        // Index pre-filter box, padded so the spherical box never clips a row
        // that PostGIS' ellipsoidal ST_DWithin would keep.
        double[] box = GeoUtils.boundingBox(lat, lng, radiusMeters * RADIUS_TOLERANCE);
        return restaurantRepository
            .findDecisionCandidates(lat, lng, radiusMeters, box[0], box[1], box[2], box[3], props.getCandidateCap())
            .stream()
            .filter(r -> !excludeIds.contains(r.getId()))
            .filter(DecisionService::isEligible)
            .filter(r -> GeoUtils.distanceMeters(lat, lng, r.getLatitude(), r.getLongitude())
                         <= radiusMeters * RADIUS_TOLERANCE)
            .toList();
    }

    /** Minimum to be a useful answer: an ID, a name, real coordinates. Nothing optional is required. */
    static boolean isEligible(Restaurant r) {
        return r.getId() != null
            && r.getName() != null && !r.getName().isBlank()
            && r.getLatitude() != null && Double.isFinite(r.getLatitude())
            && Math.abs(r.getLatitude()) <= 90
            && r.getLongitude() != null && Double.isFinite(r.getLongitude())
            && Math.abs(r.getLongitude()) <= 180;
    }

    /** One menu query for the whole batch, then name/menu matching in memory. */
    private Map<Long, Evidence> moodEvidence(Mood mood, List<Restaurant> candidates) {
        List<Long> ids = candidates.stream().map(Restaurant::getId).toList();
        Map<Long, List<String>> menus = restaurantFoodRepository.findDishNamesByRestaurantIds(ids).stream()
            .collect(Collectors.groupingBy(
                RestaurantDishNameProjection::getRestaurantId,
                Collectors.mapping(RestaurantDishNameProjection::getDishName, Collectors.toList())));

        Map<Long, Evidence> evidence = new HashMap<>();
        for (Restaurant r : candidates) {
            MoodMatcher.evidence(mood, r.getName(), menus.getOrDefault(r.getId(), List.of()))
                .ifPresent(e -> evidence.put(r.getId(), e));
        }
        return evidence;
    }

    // ── Decision ──────────────────────────────────────────────────────────────

    private DecisionResponse choose(DecisionRequest req, int radiusMeters, int level, boolean widened,
                                    List<Restaurant> candidates, Map<Long, Evidence> evidence,
                                    Boolean moodMatched) {
        LocationScope scope = req.scope();
        List<Restaurant> ranked = rankingService.rank(candidates, scope.originLat(), scope.originLng(),
            PersonalizationContext.anonymous());
        Restaurant winner = selector.pick(ranked, props.getPoolSize());
        Integer distanceMeters = distanceFromUser(scope, winner);

        log.debug("decision: id={} level={} candidates={} radius={} mood={} moodMatched={}",
            winner.getId(), level, candidates.size(), radiusMeters, slug(req), moodMatched);

        return new DecisionResponse(
            UUID.randomUUID(),
            restaurantMapper.toSummary(winner),
            distanceMeters,
            reasons(scope, winner, distanceMeters, req.mood(), evidence.get(winner.getId())),
            candidates.size(),
            level,
            scope.source(),
            radiusMeters,
            slug(req),
            moodMatched,
            null,
            areaRef(scope),
            widened,
            null
        );
    }

    /** Each reason appears only when the data behind it exists. Never opening status (not evaluated). */
    List<DecisionReason> reasons(LocationScope scope, Restaurant winner, Integer distanceMeters, Mood mood,
                                 Evidence evidence) {
        List<DecisionReason> reasons = new ArrayList<>();
        reasons.add(whereReason(scope, distanceMeters));

        if (mood != null && evidence != null) {
            reasons.add(evidence.source() == MoodMatcher.Source.NAME
                ? new DecisionReason("MOOD_NAME", "Phù hợp " + mood.label())
                : new DecisionReason("MOOD_MENU", "Có " + mood.label() + ": " + evidence.dishName()));
        }

        ratingReason(winner).ifPresent(reasons::add);
        return reasons;
    }

    /** "Được đánh giá 4.5 sao (4 lượt)" — only when the restaurant really has reviews. */
    static Optional<DecisionReason> ratingReason(Restaurant r) {
        boolean rated = r.getAvgRating() != null && r.getAvgRating().signum() > 0
                     && r.getReviewCount() != null && r.getReviewCount() > 0;
        return rated
            ? Optional.of(new DecisionReason("RATING", String.format(VI, "Được đánh giá %.1f sao (%d lượt)",
                r.getAvgRating().doubleValue(), r.getReviewCount())))
            : Optional.empty();
    }

    static String formatDistance(int meters) {
        if (meters < 10) return "dưới 10 m";                 // never round 0–9 m up to a claim of "10 m"
        if (meters < 1000) return Math.round(meters / 10.0) * 10 + " m";
        return String.format(VI, "%.1f km", meters / 1000.0);
    }

    private static String slug(DecisionRequest req) {
        return req.mood() == null ? null : req.mood().slug();
    }
}
