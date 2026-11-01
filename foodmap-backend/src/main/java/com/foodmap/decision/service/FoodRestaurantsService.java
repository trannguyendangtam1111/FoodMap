package com.foodmap.decision.service;

import com.foodmap.catalog.FoodEntry;
import com.foodmap.decision.dto.AvailableFoodsResponse.ScopeDTO;
import com.foodmap.decision.dto.FoodDecisionResponse.FoodRef;
import com.foodmap.decision.dto.FoodRestaurantsResponse;
import com.foodmap.decision.dto.FoodRestaurantsResponse.Evidence;
import com.foodmap.decision.dto.FoodRestaurantsResponse.Item;
import com.foodmap.decision.scope.LocationScope;
import com.foodmap.decision.service.FoodAvailabilityService.Place;
import com.foodmap.decision.service.FoodAvailabilityService.Snapshot;
import com.foodmap.food.match.FoodEvidence;
import com.foodmap.restaurant.domain.Restaurant;
import com.foodmap.restaurant.mapper.RestaurantMapper;
import com.foodmap.restaurant.ranking.PersonalizationContext;
import com.foodmap.restaurant.ranking.RankingService;
import com.foodmap.restaurant.repository.RestaurantRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * The FoodPage list: every restaurant of a scope with evidence for one food,
 * in a stable ranking order, one page at a time.
 *
 *   the scope's {@link Snapshot} (the same one availability and the food
 *   decisions read) → restaurants with evidence for the food → the first
 *   radius stage holding any (the stage availability reports) → existing
 *   {@link RankingService} from the scope's origin → page.
 *
 * Deterministic: no random draw. Candidates are ranked in id order, and the
 * ranking's stable sort keeps equal scores in that order, so the same request
 * on the same data returns the same order. The whole matching set is ranked
 * before paging, so {@code totalElements} is the food's availability count —
 * exact, or, when the scope's rows hit the safety cap, a lower bound flagged
 * {@code totalCapped} (the same rows, so the same flag as countCapped).
 *
 * The food is never substituted and the scope never changes; only the radius
 * may widen along the scope's ladder (never for an AREA), and that is
 * reported. A distance is returned for USER scopes only.
 *
 * READ-ONLY: no writes, no ingestion, no provider.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FoodRestaurantsService {

    private final FoodAvailabilityService availability;
    private final RestaurantRepository restaurantRepository;
    private final RankingService rankingService;
    private final RestaurantMapper restaurantMapper;

    @Transactional(readOnly = true)
    public FoodRestaurantsResponse list(LocationScope scope, FoodEntry food, int page, int size) {
        Snapshot snapshot = availability.snapshot(scope);
        List<Place> withFood = snapshot.places().stream().filter(p -> p.food().has(food.key())).toList();
        int stage = withFood.stream().mapToInt(Place::stage).min().orElse(-1);

        if (stage < 0) {
            int widestStage = scope.ladder().widestStage();
            log.debug("food restaurants: none (scope={} food={})", scope, food.key());
            return new FoodRestaurantsResponse(scopeDto(scope, widestStage), foodRef(food), 0, snapshot.capped(),
                page, size, List.of());
        }

        Map<Long, Place> byId = withFood.stream()
            .filter(p -> p.stage() <= stage)
            .collect(Collectors.toMap(Place::id, Function.identity()));
        List<Restaurant> candidates = restaurantRepository.findAllById(byId.keySet()).stream()
            .sorted(Comparator.comparing(Restaurant::getId))
            .toList();
        List<Restaurant> ranked = rankingService.rank(candidates, scope.originLat(), scope.originLng(),
            PersonalizationContext.anonymous());

        long from = Math.min((long) page * size, ranked.size());
        long to = Math.min(from + size, ranked.size());
        List<Item> items = ranked.subList((int) from, (int) to).stream()
            .map(r -> item(scope, food, r, byId.get(r.getId())))
            .toList();

        log.debug("food restaurants: scope={} food={} total={} radius={} page={} size={}",
            scope, food.key(), ranked.size(), scope.ladder().radiusAt(stage), page, size);
        return new FoodRestaurantsResponse(scopeDto(scope, stage), foodRef(food), ranked.size(), snapshot.capped(),
            page, size, items);
    }

    private Item item(LocationScope scope, FoodEntry food, Restaurant r, Place place) {
        FoodEvidence evidence = place.food().evidence().get(food.key());
        return new Item(
            restaurantMapper.toSummary(r, place.food()),
            DecisionService.distanceFromUser(scope, r),         // USER only; null for AREA / CITY_CENTER
            new Evidence(evidence.source().name(), evidence.source() == FoodEvidence.Source.MENU ? evidence.dishName() : null));
    }

    /** The scope with the radius actually used for this food — never coordinates. */
    private static ScopeDTO scopeDto(LocationScope scope, int stage) {
        return new ScopeDTO(scope.source(), DecisionService.areaRef(scope), scope.ladder().radiusAt(stage),
            stage > 0, scope.label());
    }

    private static FoodRef foodRef(FoodEntry food) {
        return new FoodRef(food.key(), food.label(), food.kind().name(), food.parentCategory().key(), food.tone(),
            food.artKey());
    }
}
