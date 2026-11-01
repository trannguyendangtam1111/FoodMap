package com.foodmap.decision.service;

import com.foodmap.catalog.FoodEntry;
import com.foodmap.catalog.FoodKind;
import com.foodmap.config.properties.DecisionProperties;
import com.foodmap.decision.dto.AvailableFoodsResponse.AvailableFood;
import com.foodmap.decision.dto.DecisionReason;
import com.foodmap.decision.dto.DecisionResponse;
import com.foodmap.decision.dto.FoodDecisionResponse;
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

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Food-first decisions — the server chooses, the client only presents.
 *
 *   food:        the available foods of the scope ({@link FoodAvailabilityService}),
 *                filtered by kind and by the foods already shown, then ONE
 *                uniform draw ({@link DecisionSelector#pickUniformly}).
 *   restaurant:  the scope's restaurants WITH evidence for the requested food
 *                → restaurant exclusions → existing {@link RankingService} →
 *                weighted top-N draw ({@link DecisionSelector#pick}).
 *
 * Both read the same {@link Snapshot} as the availability endpoint for the
 * same scope, so a food's restaurantCount equals the candidates its
 * restaurant decision draws from (without exclusions, same radius) — by
 * construction, for USER, AREA and CITY_CENTER alike.
 *
 * The food is never relaxed or substituted, and the scope never changes: no
 * restaurant for it means restaurant = null with an emptyReason. Only the
 * radius may widen, along the scope's ladder (never for an AREA), and that is
 * reported. A distance is returned for USER scopes only — it is the real
 * distance from the user, never from an area or the city centre.
 *
 * READ-ONLY: no writes, no ingestion, no provider.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FoodDecisionService {

    private static final Locale VI = Locale.forLanguageTag("vi-VN");

    private final FoodAvailabilityService availability;
    private final RestaurantRepository restaurantRepository;
    private final RankingService rankingService;
    private final RestaurantMapper restaurantMapper;
    private final DecisionSelector selector;
    private final DecisionProperties props;

    // ── Food ─────────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public FoodDecisionResponse decideFood(LocationScope scope, Set<FoodKind> kinds, Set<String> excludedFoodKeys) {
        Snapshot snapshot = availability.snapshot(scope);
        List<AvailableFood> available = availability.available(snapshot, kinds);
        List<AvailableFood> options = available.stream().filter(f -> !excludedFoodKeys.contains(f.key())).toList();

        if (options.isEmpty()) {
            boolean nothing = available.isEmpty();
            log.debug("food decision: none (scope={} available={} excluded={})",
                scope, available.size(), excludedFoodKeys.size());
            return new FoodDecisionResponse(null, null, 0, false,
                nothing ? scope.ladder().widest() : scope.ladder().base(),
                nothing && scope.ladder().widestStage() > 0,
                FoodAvailabilityService.scopeDto(scope), 0,
                nothing ? FoodDecisionResponse.NO_FOOD_IN_SCOPE : FoodDecisionResponse.ALL_SHOWN);
        }

        AvailableFood chosen = selector.pickUniformly(options);
        log.debug("food decision: scope={} food={} among={} count={} radius={}",
            scope, chosen.key(), options.size(), chosen.count(), chosen.radiusMeters());
        return new FoodDecisionResponse(
            UUID.randomUUID(),
            new FoodDecisionResponse.FoodRef(chosen.key(), chosen.label(), chosen.kind(), chosen.parentCategory(),
                chosen.tone(), artKeyOf(chosen.key())),
            chosen.count(), chosen.countCapped(), chosen.radiusMeters(), chosen.radiusWidened(),
            FoodAvailabilityService.scopeDto(scope), options.size(), null);
    }

    // ── Restaurant for a food ────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public DecisionResponse decideRestaurant(LocationScope scope, FoodEntry food, Set<Long> excludedRestaurantIds) {
        Snapshot snapshot = availability.snapshot(scope);
        List<Place> withFood = snapshot.places().stream().filter(p -> p.food().has(food.key())).toList();
        int widestStage = scope.ladder().widestStage();

        for (int stage = 0; stage <= widestStage; stage++) {
            final int s = stage;
            List<Place> candidates = withFood.stream()
                .filter(p -> p.stage() <= s && !excludedRestaurantIds.contains(p.id()))
                .toList();
            if (!candidates.isEmpty()) {
                return choose(scope, food, stage, candidates);
            }
        }

        String reason = withFood.isEmpty() ? DecisionResponse.NO_FOOD_IN_SCOPE : DecisionResponse.ALL_SHOWN;
        log.debug("food restaurant decision: none (scope={} food={} withFood={} excluded={} reason={})",
            scope, food.key(), withFood.size(), excludedRestaurantIds.size(), reason);
        return new DecisionResponse(null, null, null, List.of(), 0, DecisionService.levelOf(scope, widestStage),
            scope.source(), scope.ladder().widest(), null, null,
            foodRef(food), DecisionService.areaRef(scope), widestStage > 0, reason);
    }

    private DecisionResponse choose(LocationScope scope, FoodEntry food, int stage, List<Place> candidates) {
        Map<Long, Place> byId = candidates.stream().collect(Collectors.toMap(Place::id, Function.identity()));
        List<Restaurant> restaurants = restaurantRepository.findAllById(byId.keySet());

        List<Restaurant> ranked = rankingService.rank(restaurants, scope.originLat(), scope.originLng(),
            PersonalizationContext.anonymous());
        Restaurant winner = selector.pick(ranked, props.getPoolSize());
        Place place = byId.get(winner.getId());
        Integer distanceMeters = DecisionService.distanceFromUser(scope, winner);

        log.debug("food restaurant decision: scope={} food={} id={} candidates={} radius={}",
            scope, food.key(), winner.getId(), candidates.size(), scope.ladder().radiusAt(stage));

        return new DecisionResponse(
            UUID.randomUUID(),
            restaurantMapper.toSummary(winner, place.food()),
            distanceMeters,                                     // USER only; null for AREA / CITY_CENTER
            reasons(scope, food, winner, distanceMeters, place.food().evidence().get(food.key())),
            candidates.size(),
            DecisionService.levelOf(scope, stage),
            scope.source(),
            scope.ladder().radiusAt(stage),
            null,
            null,
            foodRef(food),
            DecisionService.areaRef(scope),
            stage > 0,
            null);
    }

    /** Factual only: where (real distance or the scope's area), why the place has the food, its real rating. */
    List<DecisionReason> reasons(LocationScope scope, FoodEntry food, Restaurant winner, Integer distanceMeters,
                                 FoodEvidence evidence) {
        List<DecisionReason> reasons = new ArrayList<>();
        reasons.add(DecisionService.whereReason(scope, distanceMeters));
        if (evidence != null && evidence.source() == FoodEvidence.Source.MENU) {
            reasons.add(new DecisionReason("FOOD_MENU", "Thực đơn có " + evidence.dishName()));
        } else if (evidence != null) {
            reasons.add(new DecisionReason("FOOD_NAME", "Tên quán cho thấy có " + food.label().toLowerCase(VI)));
        }
        DecisionService.ratingReason(winner).ifPresent(reasons::add);
        return reasons;
    }

    private static DecisionResponse.FoodRef foodRef(FoodEntry food) {
        return new DecisionResponse.FoodRef(food.key(), food.label());
    }

    private String artKeyOf(String key) {
        return availability.vocabulary().get(key).map(FoodEntry::artKey).orElse(key);
    }
}
