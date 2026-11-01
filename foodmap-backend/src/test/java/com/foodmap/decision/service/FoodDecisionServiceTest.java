package com.foodmap.decision.service;

import com.foodmap.catalog.AreaCatalogLoader;
import com.foodmap.catalog.AreaPresets;
import com.foodmap.catalog.FoodCatalogLoader;
import com.foodmap.catalog.FoodEntry;
import com.foodmap.catalog.FoodKind;
import com.foodmap.catalog.FoodVocabulary;
import com.foodmap.common.util.GeoUtils;
import com.foodmap.config.properties.DecisionProperties;
import com.foodmap.decision.dto.AvailableFoodsResponse;
import com.foodmap.decision.dto.AvailableFoodsResponse.AvailableFood;
import com.foodmap.decision.dto.DecisionReason;
import com.foodmap.decision.dto.DecisionResponse;
import com.foodmap.decision.dto.FoodDecisionResponse;
import com.foodmap.decision.scope.LocationScope;
import com.foodmap.food.mapper.FoodMapper;
import com.foodmap.food.match.FoodClassifier;
import com.foodmap.food.match.FoodMatcher;
import com.foodmap.restaurant.domain.Restaurant;
import com.foodmap.restaurant.dto.AvailabilityCandidateRow;
import com.foodmap.restaurant.dto.RestaurantDishNameProjection;
import com.foodmap.restaurant.mapper.RestaurantMapper;
import com.foodmap.restaurant.ranking.PersonalizationContext;
import com.foodmap.restaurant.ranking.PlaceScorer;
import com.foodmap.restaurant.ranking.RankingProperties;
import com.foodmap.restaurant.ranking.RankingService;
import com.foodmap.restaurant.repository.RestaurantFoodRepository;
import com.foodmap.restaurant.repository.RestaurantRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Transactional;

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.random.RandomGenerator;

import static com.foodmap.decision.service.FoodAvailabilityServiceTest.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/**
 * Food-first decisions in every scope: the city centre (3 km → 5 km), a user
 * location (1 km → 3 km) and discovery areas (own radius). Restaurant rows
 * are real dev-DB rows with their real coordinates and PostGIS distances
 * (shared with FoodAvailabilityServiceTest); menus are their real FoodMap
 * menus. Every draw runs on a controlled random source.
 */
class FoodDecisionServiceTest {

    private static final FoodVocabulary VOCABULARY = FoodCatalogLoader.loadFromClasspath();

    /** All fixture rows: pho×3 / bun×2 / banh-mi / banh-xeo / nuong×2 / sushi within 3 km; tra-sua×2, pizza only at 5 km. */
    static final List<AvailabilityCandidateRow> ROWS = List.of(BANH_MI_HUYNH_HOA, BUN_DAU_82, NHA_HANG_NGON,
        PHO_HOA, SUSHI_MASA, PHO_27, TRA_SUA_85, PIZZA_HUT_86, TRA_SUA_KOI);

    /** MEAL foods available in ROWS, catalog order. */
    private static final List<String> MEALS = List.of("bun", "pho", "banh-mi", "banh-xeo", "nuong", "pizza", "sushi");

    private RestaurantRepository restaurantRepository;
    private RestaurantFoodRepository restaurantFoodRepository;
    private RankingService rankingService;
    private FoodAvailabilityService availability;
    private final DecisionProperties props = new DecisionProperties();
    private final LocationScope centre = LocationScope.cityCentre(props);

    @BeforeEach
    void setUp() {
        restaurantRepository = mock(RestaurantRepository.class);
        restaurantFoodRepository = mock(RestaurantFoodRepository.class);
        rankingService = spy(new RankingService(new PlaceScorer(new RankingProperties())));
        FoodClassifier classifier = new FoodClassifier(new FoodMatcher(VOCABULARY), restaurantFoodRepository);
        availability = new FoodAvailabilityService(restaurantRepository, classifier);
        rowsAre(ROWS);
        when(restaurantFoodRepository.findDishNamesByRestaurantIds(any())).thenReturn(MENUS.entrySet().stream()
            .flatMap(e -> e.getValue().stream().map(d -> dish(e.getKey(), d))).toList());
        when(restaurantRepository.findAllById(any())).thenAnswer(inv -> {
            Collection<Long> ids = inv.getArgument(0);
            return ENTITIES.stream().filter(r -> ids.contains(r.getId())).toList();
        });
    }

    /** The real FoodMap menus of the fixture restaurants. */
    static final Map<Long, List<String>> MENUS = Map.of(
        9L, List.of("Bánh Xèo", "Bún Thịt Nướng", "Chả Giò", "Gỏi Cuốn Tôm Thịt", "Phở Bò Tái"),
        1L, List.of("Gỏi Cuốn Tôm Thịt", "Phở Bò Tái", "Phở Gà"),
        4L, List.of("Bánh Mì Thịt Nướng", "Chả Giò", "Gỏi Cuốn Tôm Thịt"));

    private void rowsAre(List<AvailabilityCandidateRow> rows) {
        when(restaurantRepository.findAvailabilityCandidates(anyDouble(), anyDouble(), anyDouble(),
            anyDouble(), anyDouble(), anyDouble(), anyDouble(), anyInt())).thenReturn(rows);
    }

    private static RestaurantDishNameProjection dish(long id, String name) {
        return new RestaurantDishNameProjection() {
            public Long getRestaurantId() { return id; }
            public String getDishName() { return name; }
        };
    }

    /** The same real rows as entities (ratings as in the DB). */
    static final List<Restaurant> ENTITIES = List.of(
        entity(4, "Bánh Mì Huỳnh Hoa", "4.67", 3, 10.77640, 106.70080),
        entity(82, "Bún Đậu Ngõ Nhỏ Phố Nhỏ", "0.00", 0, 10.77703, 106.69978),
        entity(9, "Nhà Hàng Ngon", "4.33", 3, 10.77790, 106.69520),
        entity(1, "Phở Hòa Pasteur", "4.50", 4, 10.77620, 106.68920),
        entity(47, "Sushi Masa", "0.00", 0, 10.79051, 106.69247),
        entity(27, "Phở", "0.00", 0, 10.79526, 106.69105),
        entity(85, "Trà sữa", "0.00", 0, 10.74475, 106.71026),
        entity(86, "Pizza Hut", "0.00", 0, 10.78753, 106.66318),
        entity(75, "Trà sữa Koi", "0.00", 0, 10.73362, 106.70185));

    private static Restaurant entity(long id, String name, String rating, int reviews, double lat, double lng) {
        return Restaurant.builder().id(id).goongPlaceId("g-" + id).name(name).latitude(lat).longitude(lng)
            .avgRating(new BigDecimal(rating)).reviewCount(reviews).isActive(true).build();
    }

    // ── Controlled random sources ────────────────────────────────────────────

    /** Always draws index {@code ticket} (clamped to the bound); records every bound it was asked for. */
    private static final class Ticket implements RandomGenerator {
        final List<Integer> bounds = new ArrayList<>();
        int ticket;
        Ticket(int ticket) { this.ticket = ticket; }
        @Override public long nextLong() { return 0; }
        @Override public int nextInt(int bound) { bounds.add(bound); return Math.min(ticket, bound - 1); }
    }

    private FoodDecisionService service(RandomGenerator random) {
        return new FoodDecisionService(availability, restaurantRepository, rankingService,
            new RestaurantMapper(new FoodMapper()), new DecisionSelector(random), props);
    }

    private static FoodEntry food(String key) {
        return VOCABULARY.get(key).orElseThrow();
    }

    private static final Set<FoodKind> MEAL = EnumSet.of(FoodKind.MEAL);

    // ── Food decision ────────────────────────────────────────────────────────

    @Test
    void oneAvailableFood_isAlwaysTheAnswer() {
        rowsAre(List.of(SUSHI_MASA));
        for (int ticket = 0; ticket < 3; ticket++) {
            FoodDecisionResponse r = service(new Ticket(ticket)).decideFood(centre, MEAL, Set.of());
            assertThat(r.food().key()).isEqualTo("sushi");
            assertThat(r.restaurantCount()).isEqualTo(1);
            assertThat(r.candidateFoodCount()).isEqualTo(1);
            assertThat(r.emptyReason()).isNull();
            assertThat(r.decisionId()).isNotNull();
        }
    }

    @Test
    void multipleFoods_eachTicketPicksADifferentFood_uniformOverTheAvailableOnes() {
        Set<String> reached = new LinkedHashSet<>();
        for (int ticket = 0; ticket < MEALS.size(); ticket++) {
            Ticket random = new Ticket(ticket);
            FoodDecisionResponse r = service(random).decideFood(centre, MEAL, Set.of());
            reached.add(r.food().key());
            assertThat(random.bounds).containsExactly(MEALS.size());   // one draw, over exactly the options
        }
        // every available meal is reachable, each by exactly one of n equal tickets
        assertThat(reached).containsExactlyInAnyOrderElementsOf(MEALS);
    }

    @Test
    void uniformDistribution_notTiltedByCatalogOrderOrCounts() {
        FoodDecisionService s = service(new Random(20261003L));       // seeded → deterministic
        Map<String, Integer> hits = new HashMap<>();
        int draws = 7000;
        for (int i = 0; i < draws; i++) hits.merge(s.decideFood(centre, MEAL, Set.of()).food().key(), 1, Integer::sum);
        assertThat(hits.keySet()).containsExactlyInAnyOrderElementsOf(MEALS);
        // pho (3 restaurants, catalog #5) and pizza (1, catalog #15) are drawn alike: ~1000 each
        hits.values().forEach(n -> assertThat(n).isBetween(850, 1150));
    }

    @Test
    void unavailableFoods_andOtherKinds_areNeverSelected() {
        Set<String> reached = new HashSet<>();
        for (int ticket = 0; ticket < 20; ticket++) reached.add(service(new Ticket(ticket)).decideFood(centre, MEAL, Set.of()).food().key());
        assertThat(reached).doesNotContain("com", "chao", "lau", "che", "tra-sua", "ca-phe")   // no rows / not MEAL
            .isSubsetOf(MEALS);
    }

    @Test
    void allKinds_includeDrinks() {
        Set<String> reached = new HashSet<>();
        Set<FoodKind> all = EnumSet.allOf(FoodKind.class);
        for (int ticket = 0; ticket < 10; ticket++) reached.add(service(new Ticket(ticket)).decideFood(centre, all, Set.of()).food().key());
        assertThat(reached).contains("tra-sua").hasSize(8);       // the 7 meals + trà sữa
    }

    @Test
    void excludedFoods_areNeverSelected() {
        Set<String> excluded = Set.of("pho", "bun", "sushi");
        for (int ticket = 0; ticket < MEALS.size(); ticket++) {
            FoodDecisionResponse r = service(new Ticket(ticket)).decideFood(centre, MEAL, excluded);
            assertThat(excluded).doesNotContain(r.food().key());
            assertThat(r.candidateFoodCount()).isEqualTo(4);
        }
    }

    @Test
    void allAvailableFoodsExcluded_isAllShown() {
        FoodDecisionResponse r = service(new Ticket(0)).decideFood(centre, MEAL, new HashSet<>(MEALS));
        assertThat(r.food()).isNull();
        assertThat(r.decisionId()).isNull();
        assertThat(r.emptyReason()).isEqualTo("ALL_SHOWN");
        assertThat(r.candidateFoodCount()).isZero();
        assertThat(r.radiusMeters()).isEqualTo(3000);
        assertThat(r.radiusWidened()).isFalse();
    }

    @Test
    void noFoodInScope_afterWidening() {
        rowsAre(List.of());
        FoodDecisionResponse r = service(new Ticket(0)).decideFood(centre, MEAL, Set.of());
        assertThat(r.food()).isNull();
        assertThat(r.emptyReason()).isEqualTo("NO_FOOD_IN_SCOPE");
        assertThat(r.radiusMeters()).isEqualTo(5000);
        assertThat(r.radiusWidened()).isTrue();
        assertThat(r.scope().locationSource()).isEqualTo("CITY_CENTER");
    }

    @Test
    void radiusWidening_isReportedPerFood_andCountsAreReal() {
        // only trà sữa among drinks: none within 3 km, two within 5 km
        FoodDecisionResponse tra = service(new Ticket(0)).decideFood(centre, EnumSet.of(FoodKind.DRINK), Set.of());
        assertThat(tra.food().key()).isEqualTo("tra-sua");
        assertThat(tra.restaurantCount()).isEqualTo(2);
        assertThat(tra.radiusMeters()).isEqualTo(5000);
        assertThat(tra.radiusWidened()).isTrue();

        // every meal: count equals the eligible restaurants with that food at the reported radius
        var snapshot = availability.snapshot(centre);
        for (int ticket = 0; ticket < MEALS.size(); ticket++) {
            FoodDecisionResponse r = service(new Ticket(ticket)).decideFood(centre, MEAL, Set.of());
            int stage = r.radiusWidened() ? 1 : 0;
            assertThat(r.restaurantCount()).isEqualTo(FoodAvailabilityService.countAt(snapshot, r.food().key(), stage));
        }
        FoodDecisionResponse pho = service(new Ticket(MEALS.indexOf("pho"))).decideFood(centre, MEAL, Set.of());
        assertThat(pho.food().key()).isEqualTo("pho");
        assertThat(pho.restaurantCount()).isEqualTo(3);            // Phở Hòa Pasteur, Phở #27, Nhà Hàng Ngon (menu)
        assertThat(pho.radiusWidened()).isFalse();
    }

    @Test
    void foodResponse_carriesOnlyPublicCatalogFields() {
        FoodDecisionResponse r = service(new Ticket(MEALS.indexOf("pho"))).decideFood(centre, MEAL, Set.of());
        assertThat(r.food()).isEqualTo(new FoodDecisionResponse.FoodRef("pho", "Phở", "MEAL", "noodle", "chili", "pho"));
        assertThat(r.scope().label()).isEqualTo("Quanh trung tâm TP.HCM");
    }

    // ── Restaurant for a food ────────────────────────────────────────────────

    private static RandomGenerator alwaysTop() { return new Ticket(0); }

    @Test
    void restaurant_isFilteredByFood_rankedByRankingService_andClassified() {
        DecisionResponse d = service(alwaysTop()).decideRestaurant(centre, food("pho"), Set.of());

        // the candidates are exactly the pho places within 3 km
        assertThat(d.candidateCount()).isEqualTo(3);
        List<Restaurant> phoPlaces = ENTITIES.stream().filter(r -> Set.of(1L, 27L, 9L).contains(r.getId())).toList();
        Restaurant expectedTop = new RankingService(new PlaceScorer(new RankingProperties()))
            .rank(phoPlaces, 10.7769, 106.7009, PersonalizationContext.anonymous()).get(0);
        assertThat(d.restaurant().getId()).isEqualTo(expectedTop.getId());
        verify(rankingService, times(1)).rank(anyList(), eq(10.7769), eq(106.7009), any());

        assertThat(d.restaurant().getFoodKeys()).contains("pho");
        assertThat(d.restaurant().getCategory()).isNotNull();
        assertThat(d.food()).isEqualTo(new DecisionResponse.FoodRef("pho", "Phở"));
        assertThat(d.locationSource()).isEqualTo("CITY_CENTER");
        assertThat(d.fallbackLevel()).isEqualTo(3);
        assertThat(d.distanceMeters()).isNull();
        assertThat(d.radiusMeters()).isEqualTo(3000);
        assertThat(d.radiusWidened()).isFalse();
        assertThat(d.emptyReason()).isNull();
        assertThat(d.area()).isNull();
        assertThat(d.reasons()).extracting(DecisionReason::code).doesNotContain("DISTANCE");
    }

    @Test
    void restaurantCount_equalsTheRestaurantDecisionsCandidates() {
        for (String key : MEALS) {
            FoodDecisionResponse f = service(new Ticket(MEALS.indexOf(key))).decideFood(centre, MEAL, Set.of());
            DecisionResponse d = service(alwaysTop()).decideRestaurant(centre, food(key), Set.of());
            assertThat(d.candidateCount()).as(key).isEqualTo(f.restaurantCount());
            assertThat(d.radiusMeters()).as(key).isEqualTo(f.radiusMeters());
            assertThat(d.radiusWidened()).as(key).isEqualTo(f.radiusWidened());
        }
    }

    @Test
    void restaurantExclusions_areNeverReturned() {
        DecisionResponse d = service(alwaysTop()).decideRestaurant(centre, food("pho"), Set.of(1L, 9L));
        assertThat(d.restaurant().getId()).isEqualTo(27L);
        assertThat(d.candidateCount()).isEqualTo(1);
    }

    @Test
    void allCandidatesExcluded_isAllShown_afterTryingTheWiderRadius() {
        DecisionResponse d = service(alwaysTop()).decideRestaurant(centre, food("pho"), Set.of(1L, 9L, 27L));
        assertThat(d.restaurant()).isNull();
        assertThat(d.emptyReason()).isEqualTo("ALL_SHOWN");
        assertThat(d.radiusMeters()).isEqualTo(5000);
        assertThat(d.radiusWidened()).isTrue();
        assertThat(d.food().key()).isEqualTo("pho");
    }

    @Test
    void foodIsNeverRelaxed_noRestaurantMeansNull() {
        DecisionResponse d = service(alwaysTop()).decideRestaurant(centre, food("com"), Set.of());   // no cơm in scope
        assertThat(d.restaurant()).isNull();
        assertThat(d.emptyReason()).isEqualTo("NO_FOOD_IN_SCOPE");
        assertThat(d.candidateCount()).isZero();
        assertThat(d.decisionId()).isNull();
        assertThat(d.reasons()).isEmpty();
        assertThat(d.food().key()).isEqualTo("com");
    }

    @Test
    void onlyTheRadiusWidens() {
        // pizza: one restaurant, at 4.3 km
        DecisionResponse d = service(alwaysTop()).decideRestaurant(centre, food("pizza"), Set.of());
        assertThat(d.restaurant().getId()).isEqualTo(86L);
        assertThat(d.radiusMeters()).isEqualTo(5000);
        assertThat(d.radiusWidened()).isTrue();
        assertThat(d.restaurant().getFoodKeys()).containsExactly("pizza");
    }

    @Test
    void everyDrawStaysWithinTheFood() {
        for (int ticket = 0; ticket < 10; ticket++) {
            DecisionResponse d = service(new Ticket(ticket)).decideRestaurant(centre, food("bun"), Set.of());
            assertThat(d.restaurant().getFoodKeys()).contains("bun");
            assertThat(d.restaurant().getId()).isIn(82L, 9L);
        }
    }

    @Test
    void reasons_areFactual_nameOrMenuEvidence_andRealRatings() {
        // Nhà Hàng Ngon has pho on its FoodMap menu; Phở #27 by its name
        DecisionResponse menu = service(alwaysTop()).decideRestaurant(centre, food("pho"), Set.of(1L, 27L));
        assertThat(menu.reasons()).extracting(DecisionReason::code).containsExactly("AREA", "FOOD_MENU", "RATING");
        assertThat(menu.reasons()).extracting(DecisionReason::label).containsExactly(
            "Quanh trung tâm TP.HCM", "Thực đơn có Phở Bò Tái", "Được đánh giá 4,3 sao (3 lượt)");

        DecisionResponse name = service(alwaysTop()).decideRestaurant(centre, food("pho"), Set.of(1L, 9L));
        assertThat(name.reasons()).extracting(DecisionReason::label)
            .containsExactly("Quanh trung tâm TP.HCM", "Tên quán cho thấy có phở");   // unrated → no rating reason
    }

    @Test
    void costsThreeReadQueries_andIsReadOnly() throws Exception {
        service(alwaysTop()).decideRestaurant(centre, food("pho"), Set.of());
        verify(restaurantRepository, times(1)).findAvailabilityCandidates(anyDouble(), anyDouble(), anyDouble(),
            anyDouble(), anyDouble(), anyDouble(), anyDouble(), anyInt());
        verify(restaurantFoodRepository, times(1)).findDishNamesByRestaurantIds(any());
        verify(restaurantRepository, times(1)).findAllById(any());
        verifyNoMoreInteractions(restaurantRepository, restaurantFoodRepository);

        for (String m : List.of("decideFood", "decideRestaurant")) {
            Transactional tx = java.util.Arrays.stream(FoodDecisionService.class.getMethods())
                .filter(x -> x.getName().equals(m)).findFirst().orElseThrow().getAnnotation(Transactional.class);
            assertThat(tx.readOnly()).isTrue();
        }
        List<String> forbidden = List.of("IngestionQueue", "IngestionWorker", "DiscoveryService",
            "RestaurantSyncService", "GoongClient", "ClickTrackingService", "CellCoverageService");
        for (Field f : FoodDecisionService.class.getDeclaredFields()) {
            assertThat(forbidden).doesNotContain(f.getType().getSimpleName());
        }
    }

    // ── Scopes: USER and AREA ────────────────────────────────────────────────

    private static final AreaPresets AREAS = AreaCatalogLoader.loadFromClasspath();
    /** A controlled user position near Phở Hòa Pasteur (Quận 3 side of Quận 1). */
    private static final double USER_LAT = 10.7768, USER_LNG = 106.6900;

    private LocationScope user(int radius) {
        return LocationScope.user(USER_LAT, USER_LNG, radius, props);
    }

    private static LocationScope area(String key) {
        return LocationScope.area(AREAS.get(key).orElseThrow());
    }

    /**
     * A stand-in for the PostGIS radius query that honours its arguments: the
     * fixture rows within {@code radius} of the queried centre, each with its
     * distance from THAT centre.
     */
    private void rowsFromGeometry() {
        when(restaurantRepository.findAvailabilityCandidates(anyDouble(), anyDouble(), anyDouble(),
            anyDouble(), anyDouble(), anyDouble(), anyDouble(), anyInt())).thenAnswer(inv -> {
                double lat = inv.getArgument(0), lng = inv.getArgument(1), radius = inv.getArgument(2);
                return ROWS.stream().map(r -> measuredFrom(r, lat, lng)).filter(r -> r.getDistanceMeters() <= radius).toList();
            });
    }

    private static AvailabilityCandidateRow measuredFrom(AvailabilityCandidateRow r, double lat, double lng) {
        double d = GeoUtils.distanceMeters(lat, lng, r.getLatitude(), r.getLongitude());
        return new AvailabilityCandidateRow() {
            public Long getId() { return r.getId(); }
            public String getName() { return r.getName(); }
            public String getCuisineType() { return r.getCuisineType(); }
            public Double getLatitude() { return r.getLatitude(); }
            public Double getLongitude() { return r.getLongitude(); }
            public Double getDistanceMeters() { return d; }
        };
    }

    /** Independent recount: fixture rows within radius of the origin whose classification has the food. */
    private long population(LocationScope scope, String key, int radius) {
        FoodClassifier classifier = new FoodClassifier(new FoodMatcher(VOCABULARY), restaurantFoodRepository);
        return ROWS.stream()
            .filter(r -> GeoUtils.distanceMeters(scope.originLat(), scope.originLng(), r.getLatitude(), r.getLongitude()) <= radius)
            .filter(r -> classifier.classify(r.getName(), r.getCuisineType(), MENUS.getOrDefault(r.getId(), List.of())).has(key))
            .count();
    }

    @Test
    void user_availableFoods_atTheUsersRadius_widenedOnlyPerFood_neverBeyondTheUserLadder() {
        rowsFromGeometry();
        AvailableFoodsResponse r = availability.availableFoods(user(1000), EnumSet.allOf(FoodKind.class));

        assertThat(r.scope().locationSource()).isEqualTo("USER");
        assertThat(r.scope().label()).isEqualTo("Gần bạn");
        assertThat(r.scope().radiusMeters()).isEqualTo(1000);
        assertThat(r.scope().area()).isNull();
        // within 1 km: Phở Hòa Pasteur (110 m) and Nhà Hàng Ngon (582 m)
        AvailableFood pho = r.foods().stream().filter(f -> f.key().equals("pho")).findFirst().orElseThrow();
        assertThat(pho).extracting(AvailableFood::count, AvailableFood::radiusMeters, AvailableFood::radiusWidened)
            .containsExactly(2, 1000, false);
        // sushi only at 1.5 km → widened to 3 km and said so
        AvailableFood sushi = r.foods().stream().filter(f -> f.key().equals("sushi")).findFirst().orElseThrow();
        assertThat(sushi).extracting(AvailableFood::count, AvailableFood::radiusMeters, AvailableFood::radiusWidened)
            .containsExactly(1, 3000, true);
        // Pizza Hut is 3.2 km away: beyond the user's widened 3 km, so not offered (no 5 km stage for 1 km)
        assertThat(r.foods()).extracting(AvailableFood::key).doesNotContain("pizza", "tra-sua");
        verify(restaurantRepository).findAvailabilityCandidates(eq(USER_LAT), eq(USER_LNG), eq(3000d),
            anyDouble(), anyDouble(), anyDouble(), anyDouble(), eq(2000));
    }

    @Test
    void user_foodDecision_isUniformOverTheUsersFoods() {
        rowsFromGeometry();
        Set<String> reached = new LinkedHashSet<>();
        for (int ticket = 0; ticket < 10; ticket++) {
            FoodDecisionResponse r = service(new Ticket(ticket)).decideFood(user(1000), MEAL, Set.of());
            reached.add(r.food().key());
            assertThat(r.scope().locationSource()).isEqualTo("USER");
        }
        // meals within 1 km (bun, pho, banh-xeo, nuong via Ngon's menu) + widened banh-mi, sushi
        assertThat(reached).containsExactlyInAnyOrder("bun", "pho", "banh-mi", "banh-xeo", "nuong", "sushi");
    }

    @Test
    void user_restaurantDecision_realDistanceFromTheUser_rankedFromTheUser() {
        rowsFromGeometry();
        DecisionResponse d = service(alwaysTop()).decideRestaurant(user(1000), food("sushi"), Set.of());

        assertThat(d.restaurant().getId()).isEqualTo(47L);
        assertThat(d.locationSource()).isEqualTo("USER");
        assertThat(d.radiusMeters()).isEqualTo(3000);
        assertThat(d.radiusWidened()).isTrue();
        assertThat(d.fallbackLevel()).isEqualTo(DecisionService.LEVEL_RADIUS_WIDENED);
        int expected = (int) Math.round(GeoUtils.distanceMeters(USER_LAT, USER_LNG, 10.79051, 106.69247));
        assertThat(d.distanceMeters()).isEqualTo(expected).isBetween(1500, 1600);
        assertThat(d.reasons().get(0)).isEqualTo(new DecisionReason("DISTANCE", "Cách bạn 1,5 km"));
        assertThat(d.area()).isNull();
        verify(rankingService).rank(anyList(), eq(USER_LAT), eq(USER_LNG), any());

        DecisionResponse pho = service(alwaysTop()).decideRestaurant(user(1000), food("pho"), Set.of());
        assertThat(pho.candidateCount()).isEqualTo(2);
        assertThat(pho.radiusWidened()).isFalse();
        assertThat(pho.fallbackLevel()).isEqualTo(DecisionService.LEVEL_EXACT);
        assertThat(pho.distanceMeters()).isNotNull();
    }

    @Test
    void user_emptyAfterTheWidestUserRadius_isNoFoodInScope_neverAnotherScope() {
        rowsFromGeometry();
        DecisionResponse d = service(alwaysTop()).decideRestaurant(user(1000), food("pizza"), Set.of());
        assertThat(d.restaurant()).isNull();
        assertThat(d.emptyReason()).isEqualTo("NO_FOOD_IN_SCOPE");
        assertThat(d.radiusMeters()).isEqualTo(3000);           // user ladder ends at 3 km, not the city's 5 km
        assertThat(d.radiusWidened()).isTrue();
        assertThat(d.locationSource()).isEqualTo("USER");
        assertThat(d.distanceMeters()).isNull();
    }

    @Test
    void area_restaurantDecision_namesTheArea_neverADistance_rankedFromTheAreaCentre() {
        rowsFromGeometry();
        DecisionResponse d = service(alwaysTop()).decideRestaurant(area("quan-1"), food("pho"), Set.of());

        assertThat(d.locationSource()).isEqualTo("AREA");
        assertThat(d.area()).isEqualTo(new com.foodmap.decision.dto.AvailableFoodsResponse.AreaRef("quan-1", "Quận 1"));
        assertThat(d.candidateCount()).isEqualTo(2);           // Phở Hòa Pasteur (1.2 km), Nhà Hàng Ngon (menu)
        assertThat(d.radiusMeters()).isEqualTo(2000);
        assertThat(d.radiusWidened()).isFalse();
        assertThat(d.fallbackLevel()).isEqualTo(DecisionService.LEVEL_EXACT);
        assertThat(d.distanceMeters()).isNull();
        assertThat(d.reasons().get(0)).isEqualTo(new DecisionReason("AREA", "Quanh Quận 1"));
        assertThat(d.reasons()).extracting(DecisionReason::code).doesNotContain("DISTANCE");
        assertThat(d.reasons()).extracting(DecisionReason::label).noneMatch(l -> l.contains("Cách bạn") || l.contains("Gần bạn"));
        verify(rankingService).rank(anyList(), eq(10.7737), eq(106.6999), any());
        verify(restaurantRepository).findAvailabilityCandidates(eq(10.7737), eq(106.6999), eq(2000d),
            anyDouble(), anyDouble(), anyDouble(), anyDouble(), eq(2000));
    }

    @Test
    void area_neverWidens_emptyMeansEmpty() {
        rowsFromGeometry();
        // Sushi Masa is 2.04 km from the Quận 1 centre: outside its 2 km, and an area never widens
        DecisionResponse sushi = service(alwaysTop()).decideRestaurant(area("quan-1"), food("sushi"), Set.of());
        assertThat(sushi.restaurant()).isNull();
        assertThat(sushi.emptyReason()).isEqualTo("NO_FOOD_IN_SCOPE");
        assertThat(sushi.radiusMeters()).isEqualTo(2000);
        assertThat(sushi.radiusWidened()).isFalse();
        assertThat(sushi.area().key()).isEqualTo("quan-1");

        // no fixture restaurant lies within 2.5 km of the Bình Thạnh centre
        FoodDecisionResponse food = service(new Ticket(0)).decideFood(area("binh-thanh"), MEAL, Set.of());
        assertThat(food.food()).isNull();
        assertThat(food.emptyReason()).isEqualTo("NO_FOOD_IN_SCOPE");
        assertThat(food.radiusMeters()).isEqualTo(2500);
        assertThat(food.radiusWidened()).isFalse();
        assertThat(food.scope().label()).isEqualTo("Quanh Bình Thạnh");
        assertThat(food.scope().area().key()).isEqualTo("binh-thanh");
    }

    @Test
    void areaRestaurantExclusions_allShown_withoutWidening() {
        rowsFromGeometry();
        DecisionResponse d = service(alwaysTop()).decideRestaurant(area("quan-1"), food("pho"), Set.of(1L, 9L));
        assertThat(d.restaurant()).isNull();
        assertThat(d.emptyReason()).isEqualTo("ALL_SHOWN");
        assertThat(d.radiusMeters()).isEqualTo(2000);
        assertThat(d.radiusWidened()).isFalse();
    }

    @Test
    void invariant_availableCount_equalsCandidates_equalsThePopulation_inEveryScope() {
        rowsFromGeometry();
        List<LocationScope> scopes = List.of(centre, user(1000), area("quan-1"), area("quan-3"));
        for (LocationScope scope : scopes) {
            List<AvailableFood> foods = availability.availableFoods(scope, EnumSet.allOf(FoodKind.class)).foods();
            assertThat(foods).as(scope.toString()).isNotEmpty();
            for (AvailableFood f : foods) {
                DecisionResponse d = service(alwaysTop()).decideRestaurant(scope, food(f.key()), Set.of());
                String what = scope + " " + f.key();
                assertThat(d.candidateCount()).as(what).isEqualTo(f.count());
                assertThat(d.radiusMeters()).as(what).isEqualTo(f.radiusMeters());
                assertThat(d.radiusWidened()).as(what).isEqualTo(f.radiusWidened());
                assertThat(d.locationSource()).as(what).isEqualTo(scope.source());
                assertThat(d.restaurant().getFoodKeys()).as(what).contains(f.key());
                assertThat((long) f.count()).as(what).isEqualTo(population(scope, f.key(), f.radiusMeters()));
            }
        }
    }

    // ── Count semantics: the food's count is exact or flagged as a lower bound ──

    @Test
    void restaurantCount_isExact_underTheCap() {
        FoodDecisionResponse d = service(new Ticket(0)).decideFood(centre, MEAL, Set.of());
        assertThat(d.restaurantCountCapped()).isFalse();
    }

    @Test
    void cappedRead_flagsTheCount_withoutChangingTheDraw() {
        List<AvailabilityCandidateRow> rows = FoodAvailabilityServiceTest.generatedRows(10,
            FoodAvailabilityService.CANDIDATE_SAFETY_CAP - 10);
        rowsAre(rows);
        // the same uniform draw over the same available foods (MEAL: only phở among these rows)
        Ticket ticket = new Ticket(0);
        FoodDecisionResponse d = service(ticket).decideFood(centre, MEAL, Set.of());
        assertThat(d.food().key()).isEqualTo("pho");
        assertThat(d.restaurantCount()).isEqualTo(10);
        assertThat(d.restaurantCountCapped()).isTrue();
        assertThat(ticket.bounds).containsExactly(1);
        // exclusions and the empty state are unchanged
        FoodDecisionResponse none = service(new Ticket(0)).decideFood(centre, MEAL, Set.of("pho"));
        assertThat(none.food()).isNull();
        assertThat(none.emptyReason()).isEqualTo("ALL_SHOWN");
        assertThat(none.restaurantCountCapped()).isFalse();
    }
}
