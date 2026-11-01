package com.foodmap.decision.service;

import com.foodmap.catalog.AreaCatalogLoader;
import com.foodmap.catalog.AreaPresets;
import com.foodmap.catalog.FoodCatalogLoader;
import com.foodmap.catalog.FoodEntry;
import com.foodmap.catalog.FoodKind;
import com.foodmap.catalog.FoodVocabulary;
import com.foodmap.common.util.GeoUtils;
import com.foodmap.config.properties.DecisionProperties;
import com.foodmap.decision.dto.AvailableFoodsResponse.AvailableFood;
import com.foodmap.decision.dto.FoodRestaurantsResponse;
import com.foodmap.decision.dto.FoodRestaurantsResponse.Evidence;
import com.foodmap.decision.dto.FoodRestaurantsResponse.Item;
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
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Random;
import java.util.random.RandomGenerator;

import static com.foodmap.decision.service.FoodDecisionServiceTest.ENTITIES;
import static com.foodmap.decision.service.FoodDecisionServiceTest.MENUS;
import static com.foodmap.decision.service.FoodDecisionServiceTest.ROWS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/**
 * The FoodPage list in every scope, on the real dev-DB fixture rows and menus
 * shared with FoodDecisionServiceTest: only restaurants with evidence for the
 * food, the existing ranking order, stable across calls, paged without
 * truncating the total — which is the food's availability count.
 */
class FoodRestaurantsServiceTest {

    private static final FoodVocabulary VOCABULARY = FoodCatalogLoader.loadFromClasspath();
    private static final AreaPresets AREAS = AreaCatalogLoader.loadFromClasspath();
    private static final double USER_LAT = 10.7768, USER_LNG = 106.6900;

    private RestaurantRepository restaurantRepository;
    private RestaurantFoodRepository restaurantFoodRepository;
    private RankingService rankingService;
    private FoodAvailabilityService availability;
    private FoodRestaurantsService service;
    private final DecisionProperties props = new DecisionProperties();
    private final LocationScope centre = LocationScope.cityCentre(props);
    /** Order in which the mocked findAllById returns entities (the database guarantees none). */
    private RandomGenerator entityOrder = new Random(1);

    @BeforeEach
    void setUp() {
        restaurantRepository = mock(RestaurantRepository.class);
        restaurantFoodRepository = mock(RestaurantFoodRepository.class);
        rankingService = spy(new RankingService(new PlaceScorer(new RankingProperties())));
        FoodClassifier classifier = new FoodClassifier(new FoodMatcher(VOCABULARY), restaurantFoodRepository);
        availability = new FoodAvailabilityService(restaurantRepository, classifier);
        service = new FoodRestaurantsService(availability, restaurantRepository, rankingService,
            new RestaurantMapper(new FoodMapper()));
        rowsFromGeometry();
        when(restaurantFoodRepository.findDishNamesByRestaurantIds(any())).thenReturn(MENUS.entrySet().stream()
            .flatMap(e -> e.getValue().stream().map(d -> dish(e.getKey(), d))).toList());
        when(restaurantRepository.findAllById(any())).thenAnswer(inv -> {
            Collection<Long> ids = inv.getArgument(0);
            List<Restaurant> found = new ArrayList<>(ENTITIES.stream().filter(r -> ids.contains(r.getId())).toList());
            Collections.shuffle(found, new Random(entityOrder.nextLong()));
            return found;
        });
    }

    /** The PostGIS radius query, honouring its centre and radius (as FoodDecisionServiceTest). */
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

    private static RestaurantDishNameProjection dish(long id, String name) {
        return new RestaurantDishNameProjection() {
            public Long getRestaurantId() { return id; }
            public String getDishName() { return name; }
        };
    }

    private static FoodEntry food(String key) {
        return VOCABULARY.get(key).orElseThrow();
    }

    private LocationScope user(int radius) {
        return LocationScope.user(USER_LAT, USER_LNG, radius, props);
    }

    private static LocationScope area(String key) {
        return LocationScope.area(AREAS.get(key).orElseThrow());
    }

    private static List<Long> ids(FoodRestaurantsResponse r) {
        return r.items().stream().map(i -> i.restaurant().getId()).toList();
    }

    /** Independent recount: fixture rows within radius of the origin whose classification has the food. */
    private long population(LocationScope scope, String key, int radius) {
        FoodClassifier classifier = new FoodClassifier(new FoodMatcher(VOCABULARY), restaurantFoodRepository);
        return ROWS.stream()
            .filter(r -> GeoUtils.distanceMeters(scope.originLat(), scope.originLng(), r.getLatitude(), r.getLongitude()) <= radius)
            .filter(r -> classifier.classify(r.getName(), r.getCuisineType(), MENUS.getOrDefault(r.getId(), List.of())).has(key))
            .count();
    }

    // ── Content ──────────────────────────────────────────────────────────────

    @Test
    void knownFood_onlyRestaurantsWithEvidence_rankedByTheRankingService_classified() {
        FoodRestaurantsResponse r = service.list(centre, food("pho"), 0, 20);

        // Phở Hòa Pasteur and "Phở" by name, Nhà Hàng Ngon by its menu — all within 3 km of the centre
        assertThat(r.totalElements()).isEqualTo(3);
        assertThat(r.totalCapped()).isFalse();                 // exact: the rows read stayed under the cap
        assertThat(ids(r)).containsExactlyInAnyOrder(1L, 27L, 9L);
        assertThat(r.food().key()).isEqualTo("pho");
        assertThat(r.food().label()).isEqualTo("Phở");
        assertThat(r.page()).isZero();
        assertThat(r.size()).isEqualTo(20);
        assertThat(r.scope().locationSource()).isEqualTo("CITY_CENTER");
        assertThat(r.scope().label()).isEqualTo("Quanh trung tâm TP.HCM");
        assertThat(r.scope().radiusMeters()).isEqualTo(3000);
        assertThat(r.scope().radiusWidened()).isFalse();
        for (Item item : r.items()) {
            assertThat(item.restaurant().getFoodKeys()).contains("pho");
            assertThat(item.restaurant().getCategory()).isNotNull();
            assertThat(item.evidence()).isNotNull();
        }

        // exactly the existing ranking from the city centre, applied to the whole matching set
        List<Restaurant> expected = new RankingService(new PlaceScorer(new RankingProperties())).rank(
            ENTITIES.stream().filter(e -> List.of(1L, 27L, 9L).contains(e.getId())).sorted(java.util.Comparator.comparing(Restaurant::getId)).toList(),
            centre.originLat(), centre.originLng(), PersonalizationContext.anonymous());
        assertThat(ids(r)).isEqualTo(expected.stream().map(Restaurant::getId).toList());
        verify(rankingService).rank(anyList(), eq(centre.originLat()), eq(centre.originLng()), any());
    }

    @Test
    void evidence_isTheServersOwn_nameOrMenuWithTheDish() {
        FoodRestaurantsResponse r = service.list(centre, food("pho"), 0, 20);
        Evidence byName = r.items().stream().filter(i -> i.restaurant().getId() == 1L).findFirst().orElseThrow().evidence();
        Evidence byMenu = r.items().stream().filter(i -> i.restaurant().getId() == 9L).findFirst().orElseThrow().evidence();
        assertThat(byName).isEqualTo(new Evidence("NAME", null));
        assertThat(byMenu).isEqualTo(new Evidence("MENU", "Phở Bò Tái"));
    }

    @Test
    void deterministic_sameOrderOnEveryCall_whateverOrderTheDatabaseReturns() {
        List<List<Long>> orders = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            entityOrder = new Random(i);
            orders.add(ids(service.list(centre, food("pho"), 0, 20)));
        }
        assertThat(orders).allSatisfy(o -> assertThat(o).isEqualTo(orders.get(0)));
    }

    @Test
    void tiesKeepAStableOrder_byId() {
        // two restaurants with identical factors except id: the same score, so the id decides
        Restaurant a = Restaurant.builder().id(200L).goongPlaceId("g-200").name("Phở A").latitude(10.7769)
            .longitude(106.7009).avgRating(BigDecimal.ZERO).reviewCount(0).isActive(true).build();
        Restaurant b = Restaurant.builder().id(100L).goongPlaceId("g-100").name("Phở B").latitude(10.7769)
            .longitude(106.7009).avgRating(BigDecimal.ZERO).reviewCount(0).isActive(true).build();
        when(restaurantRepository.findAvailabilityCandidates(anyDouble(), anyDouble(), anyDouble(),
            anyDouble(), anyDouble(), anyDouble(), anyDouble(), anyInt())).thenReturn(List.of(
                FoodAvailabilityServiceTest.row(200, "Phở A", null, 10.7769, 106.7009, 10.0),
                FoodAvailabilityServiceTest.row(100, "Phở B", null, 10.7769, 106.7009, 10.0)));
        for (List<Restaurant> dbOrder : List.of(List.of(a, b), List.of(b, a))) {
            doReturn(dbOrder).when(restaurantRepository).findAllById(any());
            assertThat(ids(service.list(centre, food("pho"), 0, 20))).containsExactly(100L, 200L);
        }
    }

    @Test
    void noRandomness_noSelector_noRandomSource() {
        for (Field f : FoodRestaurantsService.class.getDeclaredFields()) {
            assertThat(f.getType()).isNotIn(DecisionSelector.class, RandomGenerator.class, Random.class);
        }
    }

    // ── Pagination ───────────────────────────────────────────────────────────

    @Test
    void pages_concatenateToTheFullOrder_andTotalIsTheWholeSet() {
        List<Long> full = ids(service.list(centre, food("pho"), 0, 50));
        List<Long> paged = new ArrayList<>();
        for (int page = 0; page < 3; page++) {
            FoodRestaurantsResponse r = service.list(centre, food("pho"), page, 1);
            assertThat(r.totalElements()).isEqualTo(3);      // never just the page
            assertThat(r.items()).hasSize(1);
            assertThat(r.page()).isEqualTo(page);
            assertThat(r.size()).isEqualTo(1);
            paged.addAll(ids(r));
        }
        assertThat(paged).isEqualTo(full);

        FoodRestaurantsResponse past = service.list(centre, food("pho"), 3, 1);
        assertThat(past.items()).isEmpty();
        assertThat(past.totalElements()).isEqualTo(3);
        FoodRestaurantsResponse twoThenOne = service.list(centre, food("pho"), 1, 2);
        assertThat(ids(twoThenOne)).containsExactly(full.get(2));
        assertThat(service.list(centre, food("pho"), Integer.MAX_VALUE, 50).items()).isEmpty();   // no overflow
    }

    // ── Radius and empty ─────────────────────────────────────────────────────

    @Test
    void widened_onlyWhenTheBaseRadiusHasNone_andSaidSo() {
        FoodRestaurantsResponse pizza = service.list(centre, food("pizza"), 0, 20);   // Pizza Hut is 4.3 km out
        assertThat(ids(pizza)).containsExactly(86L);
        assertThat(pizza.scope().radiusMeters()).isEqualTo(5000);
        assertThat(pizza.scope().radiusWidened()).isTrue();
    }

    @Test
    void noRestaurantForTheFood_isEmpty_neverAnotherFood() {
        FoodRestaurantsResponse lau = service.list(centre, food("lau"), 0, 20);
        assertThat(lau.totalElements()).isZero();
        assertThat(lau.items()).isEmpty();
        assertThat(lau.food().key()).isEqualTo("lau");
        assertThat(lau.scope().radiusMeters()).isEqualTo(5000);
        assertThat(lau.scope().radiusWidened()).isTrue();

        // an area never widens: Sushi Masa is 2.04 km from the Quận 1 centre, outside its 2 km
        FoodRestaurantsResponse sushi = service.list(area("quan-1"), food("sushi"), 0, 20);
        assertThat(sushi.totalElements()).isZero();
        assertThat(sushi.scope().radiusMeters()).isEqualTo(2000);
        assertThat(sushi.scope().radiusWidened()).isFalse();
        assertThat(sushi.scope().area().key()).isEqualTo("quan-1");
    }

    // ── Scopes: distance for USER only ───────────────────────────────────────

    @Test
    void user_realDistanceFromTheUser_rankedFromTheUser() {
        FoodRestaurantsResponse r = service.list(user(1000), food("pho"), 0, 20);
        assertThat(r.scope().locationSource()).isEqualTo("USER");
        assertThat(r.scope().label()).isEqualTo("Gần bạn");
        assertThat(r.totalElements()).isEqualTo(2);     // Phở Hòa Pasteur (110 m), Nhà Hàng Ngon (menu, 582 m)
        assertThat(r.items()).allSatisfy(i -> assertThat(i.distanceMeters()).isNotNull());
        Item pho = r.items().stream().filter(i -> i.restaurant().getId() == 1L).findFirst().orElseThrow();
        assertThat(pho.distanceMeters()).isEqualTo(110);
        verify(rankingService).rank(anyList(), eq(USER_LAT), eq(USER_LNG), any());
    }

    @Test
    void area_andCityCentre_neverADistance_neverCoordinates() {
        for (LocationScope scope : List.of(centre, area("quan-1"))) {
            FoodRestaurantsResponse r = service.list(scope, food("pho"), 0, 20);
            assertThat(r.items()).as(scope.toString()).isNotEmpty();
            assertThat(r.items()).as(scope.toString()).allSatisfy(i -> assertThat(i.distanceMeters()).isNull());
        }
        FoodRestaurantsResponse q1 = service.list(area("quan-1"), food("pho"), 0, 20);
        assertThat(q1.scope().locationSource()).isEqualTo("AREA");
        assertThat(q1.scope().label()).isEqualTo("Quanh Quận 1");
        assertThat(q1.scope().toString()).doesNotContain("10.7737", "106.6999");
        verify(rankingService, times(2)).rank(anyList(), eq(10.7737), eq(106.6999), any());
    }

    // ── The invariant: list total == "Thèm gì?" count ────────────────────────

    @Test
    void invariant_totalElements_equalsTheAvailableCount_andThePopulation_inEveryScope() {
        List<LocationScope> scopes = List.of(centre, user(1000), user(3000), area("quan-1"), area("quan-3"));
        int checked = 0;
        for (LocationScope scope : scopes) {
            List<AvailableFood> foods = availability.availableFoods(scope, EnumSet.allOf(FoodKind.class)).foods();
            assertThat(foods).as(scope.toString()).isNotEmpty();
            for (AvailableFood f : foods) {
                FoodRestaurantsResponse r = service.list(scope, food(f.key()), 0, 50);
                String what = scope + " " + f.key();
                assertThat(r.totalElements()).as(what).isEqualTo(f.count());
                assertThat(r.items()).as(what).hasSize(f.count());
                assertThat(r.scope().radiusMeters()).as(what).isEqualTo(f.radiusMeters());
                assertThat(r.scope().radiusWidened()).as(what).isEqualTo(f.radiusWidened());
                assertThat((long) r.totalElements()).as(what).isEqualTo(population(scope, f.key(), f.radiusMeters()));
                assertThat(r.items()).as(what).allSatisfy(i -> assertThat(i.restaurant().getFoodKeys()).contains(f.key()));
                checked++;
            }
        }
        assertThat(checked).isGreaterThan(20);
    }

    // ── Read-only, bounded cost ──────────────────────────────────────────────

    @Test
    void costsThreeReadQueries_andIsReadOnly() throws Exception {
        service.list(centre, food("pho"), 0, 20);
        verify(restaurantRepository, times(1)).findAvailabilityCandidates(anyDouble(), anyDouble(), anyDouble(),
            anyDouble(), anyDouble(), anyDouble(), anyDouble(), anyInt());
        verify(restaurantFoodRepository, times(1)).findDishNamesByRestaurantIds(any());
        verify(restaurantRepository, times(1)).findAllById(any());
        verifyNoMoreInteractions(restaurantRepository, restaurantFoodRepository);

        Transactional tx = FoodRestaurantsService.class.getMethod("list", LocationScope.class, FoodEntry.class,
            int.class, int.class).getAnnotation(Transactional.class);
        assertThat(tx.readOnly()).isTrue();
        List<String> forbidden = List.of("IngestionQueue", "IngestionWorker", "DiscoveryService",
            "RestaurantSyncService", "GoongClient", "ClickTrackingService", "CellCoverageService");
        for (Field f : FoodRestaurantsService.class.getDeclaredFields()) {
            assertThat(forbidden).doesNotContain(f.getType().getSimpleName());
        }
    }

    // ── Count semantics: a capped read gives a lower bound, flagged ──────────

    /** The rows read hit the cap: 10 phở among them, the rest cafés. Entities to match. */
    private void cappedRead() {
        List<AvailabilityCandidateRow> rows = FoodAvailabilityServiceTest.generatedRows(10,
            FoodAvailabilityService.CANDIDATE_SAFETY_CAP - 10);
        when(restaurantRepository.findAvailabilityCandidates(anyDouble(), anyDouble(), anyDouble(),
            anyDouble(), anyDouble(), anyDouble(), anyDouble(), anyInt())).thenReturn(rows);
        org.mockito.Mockito.doAnswer(inv -> {
            Collection<Long> ids = inv.getArgument(0);
            return rows.stream().filter(r -> ids.contains(r.getId()))
                .map(r -> Restaurant.builder().id(r.getId()).goongPlaceId("g-" + r.getId()).name(r.getName())
                    .latitude(r.getLatitude()).longitude(r.getLongitude()).avgRating(BigDecimal.ZERO).reviewCount(0)
                    .isActive(true).build())
                .toList();
        }).when(restaurantRepository).findAllById(any());
    }

    @Test
    void cappedRead_totalIsALowerBound_flaggedTotalCapped_sameFlagAsTheAvailableCount() {
        cappedRead();
        FoodRestaurantsResponse r = service.list(centre, food("pho"), 0, 50);
        AvailableFood pho = availability.availableFoods(centre, EnumSet.allOf(FoodKind.class)).foods().stream()
            .filter(f -> f.key().equals("pho")).findFirst().orElseThrow();
        assertThat(r.totalElements()).isEqualTo(10).isEqualTo(pho.count());
        assertThat(r.totalCapped()).isTrue().isEqualTo(pho.countCapped());
        assertThat(r.items()).hasSize(10);
    }

    @Test
    void cappedRead_pagesCoverExactlyTheEvaluatedRows_noInventedPages() {
        cappedRead();
        List<Long> paged = new ArrayList<>();
        for (int page = 0; page < 3; page++) {
            FoodRestaurantsResponse r = service.list(centre, food("pho"), page, 4);
            assertThat(r.totalElements()).isEqualTo(10);
            assertThat(r.totalCapped()).isTrue();
            paged.addAll(ids(r));
        }
        assertThat(paged).hasSize(10).doesNotHaveDuplicates();
        assertThat(paged).isEqualTo(ids(service.list(centre, food("pho"), 0, 50)));
        assertThat(service.list(centre, food("pho"), 3, 4).items()).isEmpty();     // nothing past the evaluated set
    }

    @Test
    void cappedRead_noRestaurantForTheFood_isStillFlagged() {
        cappedRead();
        FoodRestaurantsResponse lau = service.list(centre, food("lau"), 0, 20);
        assertThat(lau.totalElements()).isZero();
        assertThat(lau.totalCapped()).isTrue();      // none among the rows read — not "none at all"
    }
}
