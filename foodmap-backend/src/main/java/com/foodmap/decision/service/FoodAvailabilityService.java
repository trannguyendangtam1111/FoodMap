package com.foodmap.decision.service;

import com.foodmap.catalog.FoodEntry;
import com.foodmap.catalog.FoodKind;
import com.foodmap.catalog.FoodVocabulary;
import com.foodmap.common.util.GeoUtils;
import com.foodmap.decision.dto.AvailableFoodsResponse;
import com.foodmap.decision.dto.AvailableFoodsResponse.AvailableFood;
import com.foodmap.decision.dto.AvailableFoodsResponse.ScopeDTO;
import com.foodmap.decision.scope.LocationScope;
import com.foodmap.food.match.FoodClassification;
import com.foodmap.food.match.FoodClassifier;
import com.foodmap.restaurant.dto.AvailabilityCandidateRow;
import com.foodmap.restaurant.repository.RestaurantRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Which catalog foods have at least one restaurant in a scope, and how many.
 *
 * A food is available when an active, eligible restaurant inside the scope's
 * radius has catalog evidence for it — its name, or a dish on its FoodMap
 * menu (see FoodMatcher). That is supply FoodMap knows; it says nothing
 * about popularity, quality, price or whether the place is open now.
 *
 * Scope: USER, AREA or CITY_CENTER (decision.scope.LocationScope), searched
 * along the scope's radius ladder: the base radius, then — only for foods
 * with no restaurant at the base — the widened radius (USER, CITY_CENTER;
 * an AREA never widens). The radius used is reported per food.
 *
 * The {@link Snapshot} (every eligible, classified restaurant in the scope,
 * tagged with the smallest radius it falls in) is the single source for
 * food availability AND for the food decisions (FoodDecisionService), so a
 * food's count is by construction the number of restaurants a decision for
 * that food draws from in the same scope.
 *
 * Restaurant eligibility is the decision API's (DecisionService#isEligible and
 * its radius re-check).
 *
 * Cost: one radius query at the widest radius + one batched menu query.
 * READ-ONLY.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FoodAvailabilityService {

    /** Safety bound on rows read; far above current data. Hitting it is reported as countCapped. */
    static final int CANDIDATE_SAFETY_CAP = 2000;

    private final RestaurantRepository restaurantRepository;
    private final FoodClassifier foodClassifier;

    /** One eligible restaurant of the scope: the smallest radius stage holding it, and its classification. */
    public record Place(long id, int stage, FoodClassification food) {}

    /**
     * Every eligible, classified restaurant inside the scope's widest radius.
     *
     * @param capped the safety cap on rows read was hit (counts may be low)
     */
    public record Snapshot(LocationScope scope, List<Place> places, boolean capped) {

        /** Radius ladder in metres, base first. */
        public List<Integer> stages() {
            return scope.ladder().stages();
        }
    }

    @Transactional(readOnly = true)
    public AvailableFoodsResponse availableFoods(LocationScope scope, Set<FoodKind> kinds) {
        Snapshot snapshot = snapshot(scope);
        List<AvailableFood> foods = available(snapshot, kinds);
        int unavailable = (int) discoverable(kinds).stream()
            .filter(e -> foods.stream().noneMatch(f -> f.key().equals(e.key())))
            .count();
        log.debug("foods/available: scope={} eligible={} available={} unavailable={} capped={}",
            scope, snapshot.places().size(), foods.size(), unavailable, snapshot.capped());
        return new AvailableFoodsResponse(scopeDto(scope), foods, unavailable);
    }

    public FoodVocabulary vocabulary() {
        return foodClassifier.matcher().vocabulary();
    }

    /** The scope as clients see it: source, area key/label, base radius, phrase — never coordinates. */
    public static ScopeDTO scopeDto(LocationScope scope) {
        return new ScopeDTO(scope.source(), DecisionService.areaRef(scope), scope.ladder().base(), false, scope.label());
    }

    /** One radius query at the scope's widest radius + one batched menu query; nothing else. */
    @Transactional(readOnly = true)
    public Snapshot snapshot(LocationScope scope) {
        double lat = scope.originLat();
        double lng = scope.originLng();
        List<Integer> stages = scope.ladder().stages();
        int widest = scope.ladder().widest();

        double[] box = GeoUtils.boundingBox(lat, lng, widest * DecisionService.RADIUS_TOLERANCE);
        List<AvailabilityCandidateRow> rows = restaurantRepository.findAvailabilityCandidates(
            lat, lng, widest, box[0], box[1], box[2], box[3], CANDIDATE_SAFETY_CAP);
        boolean capped = rows.size() >= CANDIDATE_SAFETY_CAP;

        List<AvailabilityCandidateRow> eligible = rows.stream().filter(FoodAvailabilityService::isEligible).toList();
        Map<Long, List<String>> menus = foodClassifier.menusOf(eligible.stream().map(AvailabilityCandidateRow::getId).toList());

        List<Place> places = new ArrayList<>(eligible.size());
        for (AvailabilityCandidateRow r : eligible) {
            int stage = stageOf(r, lat, lng, stages);
            if (stage < 0) continue;
            places.add(new Place(r.getId(), stage,
                foodClassifier.classify(r.getName(), r.getCuisineType(), menus.getOrDefault(r.getId(), List.of()))));
        }
        return new Snapshot(scope, List.copyOf(places), capped);
    }

    /**
     * Discoverable foods of {@code kinds} with at least one restaurant, in
     * catalog order: the count at the base radius, or — only when the base
     * has none — at the first wider radius that has some.
     */
    public List<AvailableFood> available(Snapshot snapshot, Set<FoodKind> kinds) {
        List<AvailableFood> foods = new ArrayList<>();
        for (FoodEntry e : discoverable(kinds)) {
            for (int s = 0; s < snapshot.stages().size(); s++) {
                int count = countAt(snapshot, e.key(), s);
                if (count > 0) {
                    foods.add(new AvailableFood(e.key(), e.label(), e.kind().name(), e.parentCategory().key(),
                        e.tone(), count, snapshot.capped(), snapshot.stages().get(s), s > 0));
                    break;
                }
            }
        }
        return foods;
    }

    /** Restaurants with evidence for {@code foodKey} within stage {@code stage} (smaller stages included). */
    public static int countAt(Snapshot snapshot, String foodKey, int stage) {
        return (int) snapshot.places().stream().filter(p -> p.stage() <= stage && p.food().has(foodKey)).count();
    }

    private List<FoodEntry> discoverable(Set<FoodKind> kinds) {
        return foodClassifier.matcher().vocabulary().discoverable().stream()
            .filter(e -> kinds.contains(e.kind()))
            .toList();
    }

    /**
     * Smallest stage whose radius holds the row: PostGIS distance within the
     * radius (what ST_DWithin decides) and the decision API's spherical
     * re-check within radius × tolerance. -1 when outside every stage.
     */
    static int stageOf(AvailabilityCandidateRow r, double lat, double lng, List<Integer> stages) {
        double spherical = GeoUtils.distanceMeters(lat, lng, r.getLatitude(), r.getLongitude());
        for (int s = 0; s < stages.size(); s++) {
            int radius = stages.get(s);
            if (r.getDistanceMeters() != null && r.getDistanceMeters() <= radius
                    && spherical <= radius * DecisionService.RADIUS_TOLERANCE) {
                return s;
            }
        }
        return -1;
    }

    /** Same minimum as DecisionService#isEligible: an id, a name and real coordinates. */
    static boolean isEligible(AvailabilityCandidateRow r) {
        return r.getId() != null
            && r.getName() != null && !r.getName().isBlank()
            && r.getLatitude() != null && Double.isFinite(r.getLatitude()) && Math.abs(r.getLatitude()) <= 90
            && r.getLongitude() != null && Double.isFinite(r.getLongitude()) && Math.abs(r.getLongitude()) <= 180;
    }
}
