package com.foodmap.decision.service;

import com.foodmap.catalog.AreaCatalogLoader;
import com.foodmap.catalog.AreaPreset;
import com.foodmap.config.properties.DecisionProperties;
import com.foodmap.decision.dto.DecisionReason;
import com.foodmap.decision.dto.DecisionResponse;
import com.foodmap.decision.scope.LocationScope;
import com.foodmap.food.mapper.FoodMapper;
import com.foodmap.restaurant.domain.Restaurant;
import com.foodmap.restaurant.dto.RestaurantDishNameProjection;
import com.foodmap.restaurant.mapper.RestaurantMapper;
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
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.random.RandomGenerator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/**
 * DecisionService with mocked repositories, the real RankingService and a
 * controllable random source. PostGIS itself can't run on the H2 test DB, so
 * the radius SQL is verified at runtime; here we test everything around it.
 */
class DecisionServiceTest {

    private static final double LAT = 10.7769, LNG = 106.7009;     // user position in tests
    private static final double M_PER_DEG_LAT = 111_195;            // ≈ metres per degree of latitude
    private static final DecisionProperties PROPS = new DecisionProperties();

    private RestaurantRepository restaurantRepository;
    private RestaurantFoodRepository restaurantFoodRepository;
    private DecisionProperties props;

    @BeforeEach
    void setUp() {
        restaurantRepository = mock(RestaurantRepository.class);
        restaurantFoodRepository = mock(RestaurantFoodRepository.class);
        props = new DecisionProperties();
        when(restaurantRepository.findDecisionCandidates(anyDouble(), anyDouble(), anyDouble(), anyDouble(), anyDouble(), anyDouble(), anyDouble(), anyInt()))
            .thenReturn(List.of());
        when(restaurantFoodRepository.findDishNamesByRestaurantIds(anyCollection())).thenReturn(List.of());
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    /** Random source whose draw always lands on the given end of the pool. */
    private static RandomGenerator alwaysTop()    { return fixed(false); }
    private static RandomGenerator alwaysBottom() { return fixed(true); }
    private static RandomGenerator fixed(boolean last) {
        return new RandomGenerator() {
            @Override public long nextLong() { return 0; }
            @Override public int nextInt(int bound) { return last ? bound - 1 : 0; }
        };
    }

    private DecisionService service(RandomGenerator random) {
        RankingService ranking = new RankingService(new PlaceScorer(new RankingProperties()));
        return new DecisionService(restaurantRepository, restaurantFoodRepository, ranking,
            new RestaurantMapper(new FoodMapper()), new DecisionSelector(random), props);
    }

    /** A restaurant `metresNorth` of the test user, with only the required fields. */
    private static Restaurant place(long id, String name, double metresNorth) {
        return Restaurant.builder()
            .id(id).goongPlaceId("g-" + id).name(name)
            .latitude(LAT + metresNorth / M_PER_DEG_LAT).longitude(LNG)
            .isActive(true)
            .build();
    }

    private static Restaurant rated(Restaurant r, String rating, int reviews) {
        r.setAvgRating(new BigDecimal(rating));
        r.setReviewCount(reviews);
        return r;
    }

    private void radiusReturns(double lat, double lng, int radius, Restaurant... rows) {
        when(restaurantRepository.findDecisionCandidates(eq(lat), eq(lng), eq((double) radius), anyDouble(), anyDouble(), anyDouble(), anyDouble(), anyInt()))
            .thenReturn(List.of(rows));
    }

    private static DecisionRequest at(int radius, Mood mood, Long... exclude) {
        return new DecisionRequest(LocationScope.user(LAT, LNG, radius, PROPS), mood, Set.of(exclude));
    }

    private static List<String> codes(DecisionResponse d) {
        return d.reasons().stream().map(DecisionReason::code).toList();
    }

    // ── candidates & radius ──────────────────────────────────────────────────

    @Test
    void picksWithinRadius_andDropsRowsOutsideIt() {
        Restaurant near = place(1, "Quán Gần", 220);
        Restaurant outside = place(2, "Quán Xa", 2_200);   // a bogus row beyond 1000 m
        radiusReturns(LAT, LNG, 1000, near, outside);

        DecisionResponse d = service(alwaysBottom()).decide(at(1000, null));

        assertThat(d.restaurant().getId()).isEqualTo(1L);
        assertThat(d.candidateCount()).isEqualTo(1);
        assertThat(d.fallbackLevel()).isEqualTo(DecisionService.LEVEL_EXACT);
        assertThat(d.locationSource()).isEqualTo("USER");
        assertThat(d.radiusMeters()).isEqualTo(1000);
        assertThat(d.distanceMeters()).isBetween(215, 225);
    }

    @Test
    void excludedIds_areNeverSelected_evenAsTheOnlyCandidate() {
        Restaurant only = place(7, "Phở Một Mình", 300);
        radiusReturns(LAT, LNG, 1000, only);
        radiusReturns(LAT, LNG, 3000, only);

        DecisionResponse d = service(alwaysTop()).decide(at(1000, null, 7L));

        assertThat(d.restaurant()).isNull();
        assertThat(d.decisionId()).isNull();
        assertThat(d.candidateCount()).isZero();
        assertThat(d.fallbackLevel()).isEqualTo(DecisionService.LEVEL_RADIUS_WIDENED);
    }

    @Test
    void excludedIds_areNeverSelected_acrossManyRandomDraws() {
        List<Restaurant> rows = new ArrayList<>();
        for (long id = 1; id <= 12; id++) rows.add(place(id, "Quán " + id, id * 50));
        radiusReturns(LAT, LNG, 1000, rows.toArray(Restaurant[]::new));
        Set<Long> excluded = Set.of(1L, 2L, 3L, 5L, 8L);
        DecisionService svc = service(new Random(99));

        for (int i = 0; i < 300; i++) {
            DecisionResponse d = svc.decide(new DecisionRequest(LocationScope.user(LAT, LNG, 1000, PROPS), null, excluded));
            assertThat(excluded).doesNotContain(d.restaurant().getId());
            assertThat(d.candidateCount()).isEqualTo(7);
        }
    }

    @Test
    void sparseRestaurant_isEligible_andGetsNoInventedReasons() {
        Restaurant bare = place(3, "Restaurant D'Nyonya", 400);   // no rating, price, photos, hours
        radiusReturns(LAT, LNG, 1000, bare);

        DecisionResponse d = service(alwaysTop()).decide(at(1000, null));

        assertThat(d.restaurant().getId()).isEqualTo(3L);
        assertThat(d.restaurant().getPriceLevel()).isNull();
        assertThat(codes(d)).containsExactly("DISTANCE");
    }

    @Test
    void restaurantWithoutName_isIneligible() {
        radiusReturns(LAT, LNG, 1000, place(4, "   ", 100));
        assertThat(service(alwaysTop()).decide(at(1000, null)).restaurant()).isNull();
    }

    // ── fallbacks ──────────────────────────────────────────────────────────────

    @Test
    void emptyRequestedRadius_widensTheRadius_level2() {
        Restaurant further = place(5, "Bún Bò Xa Xa", 2_000);
        radiusReturns(LAT, LNG, 3000, further);

        DecisionResponse d = service(alwaysTop()).decide(at(1000, null));

        assertThat(d.restaurant().getId()).isEqualTo(5L);
        assertThat(d.fallbackLevel()).isEqualTo(DecisionService.LEVEL_RADIUS_WIDENED);
        assertThat(d.radiusMeters()).isEqualTo(3000);
        var order = inOrder(restaurantRepository);
        order.verify(restaurantRepository).findDecisionCandidates(eq(LAT), eq(LNG), eq(1000d), anyDouble(), anyDouble(), anyDouble(), anyDouble(), eq(200));
        order.verify(restaurantRepository).findDecisionCandidates(eq(LAT), eq(LNG), eq(3000d), anyDouble(), anyDouble(), anyDouble(), anyDouble(), eq(200));
    }

    @Test
    void indexPrefilterBox_enclosesTheWholeRadius() {
        service(alwaysTop()).decide(at(1000, null));

        var sw = org.mockito.ArgumentCaptor.forClass(Double.class);
        var ne = org.mockito.ArgumentCaptor.forClass(Double.class);
        var swLng = org.mockito.ArgumentCaptor.forClass(Double.class);
        var neLng = org.mockito.ArgumentCaptor.forClass(Double.class);
        verify(restaurantRepository).findDecisionCandidates(eq(LAT), eq(LNG), eq(1000d),
            sw.capture(), swLng.capture(), ne.capture(), neLng.capture(), eq(200));

        double northEdgeM = (ne.getValue() - LAT) * M_PER_DEG_LAT;
        double southEdgeM = (LAT - sw.getValue()) * M_PER_DEG_LAT;
        double eastEdgeM  = com.foodmap.common.util.GeoUtils.distanceMeters(LAT, LNG, LAT, neLng.getValue());
        double westEdgeM  = com.foodmap.common.util.GeoUtils.distanceMeters(LAT, LNG, LAT, swLng.getValue());
        assertThat(List.of(northEdgeM, southEdgeM, eastEdgeM, westEdgeM)).allSatisfy(m -> assertThat(m).isGreaterThan(1000));
    }

    @Test
    void radiusAlreadyAtMaximum_isNotWidened() {
        DecisionResponse d = service(alwaysTop()).decide(at(5000, null));
        assertThat(d.restaurant()).isNull();
        assertThat(d.fallbackLevel()).isEqualTo(DecisionService.LEVEL_EXACT);
        verify(restaurantRepository).findDecisionCandidates(eq(LAT), eq(LNG), eq(5000d), anyDouble(), anyDouble(), anyDouble(), anyDouble(), eq(200));
        verifyNoMoreInteractions(restaurantRepository);
    }

    @Test
    void noLocation_usesCityCentrePool_level3_andNeverClaimsDistance() {
        Restaurant central = place(6, "Bún Bò Trung Tâm", 500);
        radiusReturns(props.getFallbackLat(), props.getFallbackLng(), 3000, central);

        DecisionResponse d = service(alwaysTop()).decide(new DecisionRequest(LocationScope.cityCentre(props), null, Set.of()));

        assertThat(d.restaurant().getId()).isEqualTo(6L);
        assertThat(d.fallbackLevel()).isEqualTo(DecisionService.LEVEL_CITY_CENTER);
        assertThat(d.locationSource()).isEqualTo("CITY_CENTER");
        assertThat(d.distanceMeters()).isNull();
        assertThat(d.reasons()).extracting(DecisionReason::label).containsExactly("Quanh trung tâm TP.HCM");
    }

    // ── mood ───────────────────────────────────────────────────────────────────

    @Test
    void mood_matchedByName_level0() {
        radiusReturns(LAT, LNG, 1000, place(10, "Cafe Mộc", 100), place(11, "Phở Hòa Pasteur", 600));

        DecisionResponse d = service(alwaysTop()).decide(at(1000, Mood.MON_NUOC));

        assertThat(d.restaurant().getId()).isEqualTo(11L);
        assertThat(d.moodMatched()).isTrue();
        assertThat(d.mood()).isEqualTo("mon-nuoc");
        assertThat(d.candidateCount()).isEqualTo(1);
        assertThat(d.fallbackLevel()).isEqualTo(DecisionService.LEVEL_EXACT);
        assertThat(d.reasons()).extracting(DecisionReason::label).contains("Phù hợp món nước");
    }

    @Test
    void mood_matchedByMenu_reasonNamesTheDish() {
        radiusReturns(LAT, LNG, 1000, place(12, "Quán Cơm Bà Cụ", 300));
        RestaurantDishNameProjection dish = mock(RestaurantDishNameProjection.class);
        when(dish.getRestaurantId()).thenReturn(12L);
        when(dish.getDishName()).thenReturn("Bánh Canh Cua");
        when(restaurantFoodRepository.findDishNamesByRestaurantIds(anyCollection())).thenReturn(List.of(dish));

        DecisionResponse d = service(alwaysTop()).decide(at(1000, Mood.MON_NUOC));

        assertThat(d.moodMatched()).isTrue();
        assertThat(d.reasons()).extracting(DecisionReason::label).contains("Có món nước: Bánh Canh Cua");
    }

    @Test
    void mood_withNoFittingCandidate_isRelaxed_level1() {
        radiusReturns(LAT, LNG, 1000, place(13, "Cafe Mộc", 100), place(14, "Pizza Hut", 200));

        DecisionResponse d = service(alwaysTop()).decide(at(1000, Mood.MON_NUOC));

        assertThat(d.restaurant()).isNotNull();
        assertThat(d.moodMatched()).isFalse();
        assertThat(d.fallbackLevel()).isEqualTo(DecisionService.LEVEL_MOOD_RELAXED);
        assertThat(d.candidateCount()).isEqualTo(2);
        assertThat(codes(d)).doesNotContain("MOOD_NAME", "MOOD_MENU");
    }

    @Test
    void noMood_meansNoMoodFields_andNoMenuQuery() {
        radiusReturns(LAT, LNG, 1000, place(15, "Phở Hòa", 100));

        DecisionResponse d = service(alwaysTop()).decide(at(1000, null));

        assertThat(d.mood()).isNull();
        assertThat(d.moodMatched()).isNull();
        verifyNoMoreInteractions(restaurantFoodRepository);
    }

    // ── ranking & pool ─────────────────────────────────────────────────────────

    @Test
    void ranking_putsCloserAndBetterRatedFirst() {
        Restaurant farUnrated  = place(20, "Xa", 900);
        Restaurant nearRated   = rated(place(21, "Gần, có đánh giá", 150), "4.5", 12);
        Restaurant nearUnrated = place(22, "Gần", 160);
        radiusReturns(LAT, LNG, 1000, farUnrated, nearUnrated, nearRated);

        // ticket 0 → rank 1 of the existing RankingService order
        assertThat(service(alwaysTop()).decide(at(1000, null)).restaurant().getId()).isEqualTo(21L);
    }

    @Test
    void draw_neverLeavesTheTop10() {
        List<Restaurant> rows = new ArrayList<>();
        for (long id = 1; id <= 15; id++) rows.add(place(id, "Quán " + id, id * 50));   // nearer = better
        radiusReturns(LAT, LNG, 1000, rows.toArray(Restaurant[]::new));

        // last ticket → the 10th-ranked candidate, never 11..15
        DecisionResponse d = service(alwaysBottom()).decide(at(1000, null));
        assertThat(d.restaurant().getId()).isEqualTo(10L);
        assertThat(d.candidateCount()).isEqualTo(15);
    }

    // ── response & reasons ─────────────────────────────────────────────────────

    @Test
    void eachDecision_hasItsOwnId() {
        radiusReturns(LAT, LNG, 1000, place(30, "Quán", 100));
        DecisionService svc = service(alwaysTop());
        assertThat(svc.decide(at(1000, null)).decisionId())
            .isNotNull()
            .isNotEqualTo(svc.decide(at(1000, null)).decisionId());
    }

    @Test
    void reasons_areFactual() {
        radiusReturns(LAT, LNG, 1000, rated(place(31, "Phở Hòa", 450), "4.5", 12));

        DecisionResponse d = service(alwaysTop()).decide(at(1000, Mood.MON_NUOC));

        assertThat(d.reasons()).extracting(DecisionReason::label).containsExactly(
            "Cách bạn 450 m", "Phù hợp món nước", "Được đánh giá 4,5 sao (12 lượt)");
        // opening hours are never evaluated, so never claimed
        assertThat(d.reasons()).extracting(DecisionReason::label).noneMatch(l -> l.contains("mở"));
    }

    @Test
    void ratingReason_requiresReviews() {
        Restaurant r = place(32, "Quán", 100);
        r.setAvgRating(new BigDecimal("4.0"));   // rating without any review count → not claimed
        radiusReturns(LAT, LNG, 1000, r);
        assertThat(codes(service(alwaysTop()).decide(at(1000, null)))).doesNotContain("RATING");
    }

    @Test
    void distanceFormatting_isVietnamese() {
        assertThat(DecisionService.formatDistance(0)).isEqualTo("dưới 10 m");
        assertThat(DecisionService.formatDistance(4)).isEqualTo("dưới 10 m");
        assertThat(DecisionService.formatDistance(12)).isEqualTo("10 m");
        assertThat(DecisionService.formatDistance(222)).isEqualTo("220 m");
        assertThat(DecisionService.formatDistance(1234)).isEqualTo("1,2 km");
    }

    // ── read-only guarantee ────────────────────────────────────────────────────

    @Test
    void onlyReadsRestaurantsAndMenus() {
        radiusReturns(LAT, LNG, 1000, place(40, "Phở", 100));
        service(alwaysTop()).decide(at(1000, Mood.MON_NUOC));

        verify(restaurantRepository).findDecisionCandidates(eq(LAT), eq(LNG), eq(1000d), anyDouble(), anyDouble(), anyDouble(), anyDouble(), eq(200));
        verify(restaurantFoodRepository).findDishNamesByRestaurantIds(any());
        verifyNoMoreInteractions(restaurantRepository, restaurantFoodRepository);   // no save/delete/update
    }

    @Test
    void hasNoIngestionOrProviderDependencies_andRunsReadOnly() throws Exception {
        List<String> forbidden = List.of("IngestionQueue", "IngestionWorker", "DiscoveryService",
            "RestaurantSyncService", "GoongClient", "ClickTrackingService", "CellCoverageService");
        for (Field f : DecisionService.class.getDeclaredFields()) {
            assertThat(forbidden).doesNotContain(f.getType().getSimpleName());
        }
        Transactional tx = DecisionService.class
            .getMethod("decide", DecisionRequest.class).getAnnotation(Transactional.class);
        assertThat(tx).isNotNull();
        assertThat(tx.readOnly()).isTrue();
    }

    // ── scopes: AREA and the city-centre ladder ────────────────────────────────

    @Test
    void area_searchesAroundTheAreaCentre_namesTheArea_neverADistance_neverWidens() {
        AreaPreset quan1 = AreaCatalogLoader.loadFromClasspath().get("quan-1").orElseThrow();
        Restaurant inside = place(50, "Quán Quận Một", 300);           // ≈ 665 m from the Quận 1 centre
        radiusReturns(10.7737, 106.6999, 2000, inside);
        LocationScope area = LocationScope.area(quan1);

        DecisionResponse d = service(alwaysTop()).decide(new DecisionRequest(area, null, Set.of()));

        assertThat(d.restaurant().getId()).isEqualTo(50L);
        assertThat(d.locationSource()).isEqualTo("AREA");
        assertThat(d.area().key()).isEqualTo("quan-1");
        assertThat(d.fallbackLevel()).isEqualTo(DecisionService.LEVEL_EXACT);
        assertThat(d.radiusMeters()).isEqualTo(2000);
        assertThat(d.radiusWidened()).isFalse();
        assertThat(d.distanceMeters()).isNull();
        assertThat(d.reasons()).extracting(DecisionReason::label).containsExactly("Quanh Quận 1");

        DecisionResponse empty = service(alwaysTop()).decide(new DecisionRequest(area, null, Set.of(50L)));
        assertThat(empty.restaurant()).isNull();
        assertThat(empty.emptyReason()).isEqualTo(DecisionResponse.NO_RESTAURANT_IN_SCOPE);
        assertThat(empty.radiusMeters()).isEqualTo(2000);
        assertThat(empty.radiusWidened()).isFalse();
        verify(restaurantRepository, org.mockito.Mockito.never()).findDecisionCandidates(anyDouble(), anyDouble(),
            org.mockito.ArgumentMatchers.doubleThat(r -> r != 2000d), anyDouble(), anyDouble(), anyDouble(), anyDouble(), anyInt());
    }

    @Test
    void cityCentre_widensTo5km_onlyWhenNothingWithin3km_andSaysSo() {
        Restaurant far = place(51, "Quán Xa Trung Tâm", 4000);
        radiusReturns(props.getFallbackLat(), props.getFallbackLng(), 5000, far);

        DecisionResponse d = service(alwaysTop()).decide(new DecisionRequest(LocationScope.cityCentre(props), null, Set.of()));

        assertThat(d.restaurant().getId()).isEqualTo(51L);
        assertThat(d.locationSource()).isEqualTo("CITY_CENTER");
        assertThat(d.fallbackLevel()).isEqualTo(DecisionService.LEVEL_CITY_CENTER);
        assertThat(d.radiusMeters()).isEqualTo(5000);
        assertThat(d.radiusWidened()).isTrue();
        assertThat(d.distanceMeters()).isNull();
    }
}
