package com.foodmap.decision.service;

import com.foodmap.catalog.FoodCatalogLoader;
import com.foodmap.catalog.FoodKind;
import com.foodmap.config.properties.DecisionProperties;
import com.foodmap.decision.dto.AvailableFoodsResponse;
import com.foodmap.decision.dto.AvailableFoodsResponse.AvailableFood;
import com.foodmap.decision.scope.LocationScope;
import com.foodmap.food.match.FoodClassifier;
import com.foodmap.food.match.FoodMatcher;
import com.foodmap.restaurant.dto.AvailabilityCandidateRow;
import com.foodmap.restaurant.dto.RestaurantDishNameProjection;
import com.foodmap.restaurant.repository.RestaurantFoodRepository;
import com.foodmap.restaurant.repository.RestaurantRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Transactional;

import java.lang.reflect.Field;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/**
 * Available foods around the city centre (10.7769, 106.7009; 3 km, widened
 * to 5 km). Rows are real dev-DB restaurants with their real coordinates and
 * PostGIS distances from the centre (snapshot 2026-10-03).
 */
class FoodAvailabilityServiceTest {

    private RestaurantRepository restaurantRepository;
    private RestaurantFoodRepository restaurantFoodRepository;
    private FoodAvailabilityService service;

    private static final Set<FoodKind> ALL = EnumSet.allOf(FoodKind.class);
    private static final LocationScope CITY = LocationScope.cityCentre(new DecisionProperties());

    @BeforeEach
    void setUp() {
        restaurantRepository = mock(RestaurantRepository.class);
        restaurantFoodRepository = mock(RestaurantFoodRepository.class);
        FoodClassifier classifier = new FoodClassifier(
            new FoodMatcher(FoodCatalogLoader.loadFromClasspath()), restaurantFoodRepository);
        service = new FoodAvailabilityService(restaurantRepository, classifier);
    }

    static AvailabilityCandidateRow row(long id, String name, String cuisine, double lat, double lng, double dist) {
        return new AvailabilityCandidateRow() {
            public Long getId() { return id; }
            public String getName() { return name; }
            public String getCuisineType() { return cuisine; }
            public Double getLatitude() { return lat; }
            public Double getLongitude() { return lng; }
            public Double getDistanceMeters() { return dist; }
        };
    }

    static final AvailabilityCandidateRow BANH_MI_HUYNH_HOA = row(4, "Bánh Mì Huỳnh Hoa", "Vietnamese", 10.77640, 106.70080, 56.4);
    static final AvailabilityCandidateRow BUN_DAU_82 = row(82, "Bún Đậu Ngõ Nhỏ Phố Nhỏ", null, 10.77703, 106.69978, 123.0);
    static final AvailabilityCandidateRow NHA_HANG_NGON = row(9, "Nhà Hàng Ngon", "Vietnamese", 10.77790, 106.69520, 633.1);
    static final AvailabilityCandidateRow PHO_HOA = row(1, "Phở Hòa Pasteur", "Vietnamese", 10.77620, 106.68920, 1282.0);
    static final AvailabilityCandidateRow SUSHI_MASA = row(47, "Sushi Masa", null, 10.79051, 106.69247, 1765.8);
    static final AvailabilityCandidateRow PHO_27 = row(27, "Phở", null, 10.79526, 106.69105, 2298.6);
    static final AvailabilityCandidateRow TRA_SUA_85 = row(85, "Trà sữa", null, 10.74475, 106.71026, 3701.0);
    static final AvailabilityCandidateRow PIZZA_HUT_86 = row(86, "Pizza Hut", null, 10.78753, 106.66318, 4289.7);
    static final AvailabilityCandidateRow TRA_SUA_KOI = row(75, "Trà sữa Koi", null, 10.73362, 106.70185, 4788.5);

    private static RestaurantDishNameProjection dish(long id, String name) {
        return new RestaurantDishNameProjection() {
            public Long getRestaurantId() { return id; }
            public String getDishName() { return name; }
        };
    }

    private AvailableFoodsResponse availableWith(Set<FoodKind> kinds, AvailabilityCandidateRow... rows) {
        when(restaurantRepository.findAvailabilityCandidates(anyDouble(), anyDouble(), anyDouble(),
            anyDouble(), anyDouble(), anyDouble(), anyDouble(), eq(FoodAvailabilityService.CANDIDATE_SAFETY_CAP)))
            .thenReturn(List.of(rows));
        when(restaurantFoodRepository.findDishNamesByRestaurantIds(any())).thenReturn(List.of(
            dish(9, "Bánh Xèo"), dish(9, "Bún Thịt Nướng"), dish(9, "Chả Giò"), dish(9, "Gỏi Cuốn Tôm Thịt"), dish(9, "Phở Bò Tái"),
            dish(1, "Phở Gà"), dish(4, "Bánh Mì Thịt Nướng")));
        return service.availableFoods(CITY, kinds);
    }

    private static AvailableFood food(AvailableFoodsResponse r, String key) {
        return r.foods().stream().filter(f -> f.key().equals(key)).findFirst().orElse(null);
    }

    @Test
    void zeroRestaurants_noFoodIsAvailable() {
        AvailableFoodsResponse r = availableWith(ALL);
        assertThat(r.foods()).isEmpty();
        assertThat(r.unavailableCount()).isEqualTo(20);       // every discoverable food
        assertThat(r.scope().locationSource()).isEqualTo("CITY_CENTER");
        assertThat(r.scope().label()).isEqualTo("Quanh trung tâm TP.HCM");
        assertThat(r.scope().radiusMeters()).isEqualTo(3000);
        assertThat(r.scope().area()).isNull();
    }

    @Test
    void oneRestaurant() {
        AvailableFoodsResponse r = availableWith(ALL, SUSHI_MASA);
        assertThat(r.foods()).extracting(AvailableFood::key).containsExactly("sushi");
        AvailableFood sushi = food(r, "sushi");
        assertThat(sushi.count()).isEqualTo(1);
        assertThat(sushi.radiusMeters()).isEqualTo(3000);
        assertThat(sushi.radiusWidened()).isFalse();
        assertThat(sushi.label()).isEqualTo("Sushi");
        assertThat(sushi.kind()).isEqualTo("MEAL");
        assertThat(r.unavailableCount()).isEqualTo(19);
    }

    @Test
    void multipleRestaurants_realCounts_inCatalogOrder_notByCount() {
        AvailableFoodsResponse r = availableWith(ALL, BANH_MI_HUYNH_HOA, BUN_DAU_82, NHA_HANG_NGON, PHO_HOA,
            SUSHI_MASA, PHO_27, TRA_SUA_85, PIZZA_HUT_86, TRA_SUA_KOI);

        // catalog order: tra-sua(20) bun(30) pho(50) banh-mi(100) banh-xeo(110) nuong(130) pizza(150) sushi(160)
        assertThat(r.foods()).extracting(AvailableFood::key)
            .containsExactly("tra-sua", "bun", "pho", "banh-mi", "banh-xeo", "nuong", "pizza", "sushi");
        // pho within 3 km: Phở Hòa Pasteur (name), Phở #27 (name), Nhà Hàng Ngon (menu) — 3, not Bún Đậu …Phố…
        assertThat(food(r, "pho").count()).isEqualTo(3);
        assertThat(food(r, "bun").count()).isEqualTo(2);          // Bún Đậu (name) + Nhà Hàng Ngon (menu)
        assertThat(food(r, "banh-mi").count()).isEqualTo(1);
        assertThat(food(r, "nuong").count()).isEqualTo(2);        // menus: Bún Thịt Nướng, Bánh Mì Thịt Nướng
        // none within 3 km → widened to 5 km, and said so
        assertThat(food(r, "tra-sua")).extracting(AvailableFood::count, AvailableFood::radiusMeters, AvailableFood::radiusWidened)
            .containsExactly(2, 5000, true);
        assertThat(food(r, "pizza")).extracting(AvailableFood::count, AvailableFood::radiusWidened).containsExactly(1, true);
    }

    @Test
    void countEqualsTheEligibleRestaurantsWithEvidence_noSubstitution() {
        AvailabilityCandidateRow blankName = row(999, " ", null, 10.7770, 106.7010, 10.0);   // not eligible
        AvailableFoodsResponse r = availableWith(ALL, PHO_HOA, PHO_27, BUN_DAU_82, blankName);
        // independent count: eligible rows inside 3 km whose classification has "pho"
        assertThat(food(r, "pho").count()).isEqualTo(2);
        // phở exists, so nothing else is offered in its place; foods without restaurants stay absent
        assertThat(r.foods()).extracting(AvailableFood::key).containsExactly("bun", "pho");
        assertThat(food(r, "ca-phe")).isNull();
        assertThat(food(r, "lau")).isNull();
    }

    @Test
    void kindsFilter() {
        AvailableFoodsResponse r = availableWith(EnumSet.of(FoodKind.DRINK), PHO_HOA, TRA_SUA_85);
        assertThat(r.foods()).extracting(AvailableFood::key).containsExactly("tra-sua");
        assertThat(r.unavailableCount()).isEqualTo(1);           // ca-phe: the only other discoverable drink
    }

    @Test
    void rowsOutsideEveryRadius_areNotCounted() {
        AvailabilityCandidateRow far = row(52, "Phở", null, 10.94835, 106.73474, 19000.0);
        AvailableFoodsResponse r = availableWith(ALL, far);
        assertThat(r.foods()).isEmpty();
    }

    @Test
    void oneRadiusQueryAndOneMenuQuery_readOnly() throws Exception {
        availableWith(ALL, PHO_HOA, NHA_HANG_NGON, SUSHI_MASA);
        verify(restaurantRepository, times(1)).findAvailabilityCandidates(eq(10.7769), eq(106.7009), eq(5000d),
            anyDouble(), anyDouble(), anyDouble(), anyDouble(), eq(2000));
        verify(restaurantFoodRepository, times(1)).findDishNamesByRestaurantIds(any());
        verifyNoMoreInteractions(restaurantRepository, restaurantFoodRepository);

        Transactional tx = FoodAvailabilityService.class
            .getMethod("availableFoods", LocationScope.class, Set.class).getAnnotation(Transactional.class);
        assertThat(tx.readOnly()).isTrue();
        List<String> forbidden = List.of("IngestionQueue", "IngestionWorker", "DiscoveryService",
            "RestaurantSyncService", "GoongClient", "ClickTrackingService", "CellCoverageService");
        for (Field f : FoodAvailabilityService.class.getDeclaredFields()) {
            assertThat(forbidden).doesNotContain(f.getType().getSimpleName());
        }
    }

    // ── Count semantics: exact vs capped (the safety cap on rows read) ───────

    /**
     * Generated rows at the city centre (100 m): {@code pho} restaurants named
     * "Phở …" and {@code cafes} named "Cà phê …", ids from 10 000. For counts
     * beyond the real data (548 restaurants, 242 within 5 km of the centre).
     */
    static List<AvailabilityCandidateRow> generatedRows(int pho, int cafes) {
        double lat = CITY.originLat(), lng = CITY.originLng();
        List<AvailabilityCandidateRow> rows = new java.util.ArrayList<>();
        for (int i = 0; i < pho; i++) rows.add(row(10_000 + i, "Phở số " + i, null, lat, lng, 100.0));
        for (int i = 0; i < cafes; i++) rows.add(row(20_000 + i, "Cà phê số " + i, null, lat, lng, 100.0));
        return rows;
    }

    @Test
    void exactCount_whenTheRowsReadStayUnderTheCap() {
        AvailableFoodsResponse r = availableWith(ALL, generatedRows(127, 0).toArray(AvailabilityCandidateRow[]::new));
        assertThat(food(r, "pho")).extracting(AvailableFood::count, AvailableFood::countCapped).containsExactly(127, false);
    }

    @Test
    void cappedCount_whenTheRowsReadHitTheCap_everyCountIsALowerBound() {
        // the query stopped at the cap: rows beyond it (any food) were never read
        List<AvailabilityCandidateRow> rows = generatedRows(10, FoodAvailabilityService.CANDIDATE_SAFETY_CAP - 10);
        AvailableFoodsResponse r = availableWith(ALL, rows.toArray(AvailabilityCandidateRow[]::new));
        assertThat(food(r, "ca-phe")).extracting(AvailableFood::count, AvailableFood::countCapped)
            .containsExactly(FoodAvailabilityService.CANDIDATE_SAFETY_CAP - 10, true);
        // a small count from a capped read is just as uncertain: "at least 10"
        assertThat(food(r, "pho")).extracting(AvailableFood::count, AvailableFood::countCapped).containsExactly(10, true);
    }

    @Test
    void oneRowUnderTheCap_isExact() {
        List<AvailabilityCandidateRow> rows = generatedRows(1, FoodAvailabilityService.CANDIDATE_SAFETY_CAP - 2);
        AvailableFoodsResponse r = availableWith(ALL, rows.toArray(AvailabilityCandidateRow[]::new));
        assertThat(r.foods()).allSatisfy(f -> assertThat(f.countCapped()).isFalse());
    }
}
