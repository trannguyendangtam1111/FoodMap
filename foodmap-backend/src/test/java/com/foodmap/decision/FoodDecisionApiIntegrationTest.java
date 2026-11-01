package com.foodmap.decision;

import com.foodmap.ingestion.IngestionQueue;
import com.foodmap.integration.goong.GoongClient;
import com.foodmap.restaurant.domain.Restaurant;
import com.foodmap.restaurant.dto.AreaSupplyRow;
import com.foodmap.restaurant.dto.AvailabilityCandidateRow;
import com.foodmap.restaurant.dto.RestaurantDishNameProjection;
import com.foodmap.restaurant.repository.RestaurantFoodRepository;
import com.foodmap.restaurant.repository.RestaurantRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The food-first decision endpoints through the real application (test
 * profile): routing, anonymous access, validation, response envelope, rate
 * limiting, the read-only guarantee and the USER / AREA / CITY_CENTER scopes.
 * The restaurant repository is mocked (its PostGIS queries can't run on H2)
 * with real dev-DB rows; every discovery area has 40 restaurants except
 * go-vap (3), which is therefore unavailable.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@ActiveProfiles("test")
@ExtendWith(OutputCaptureExtension.class)
class FoodDecisionApiIntegrationTest {

    private static final AtomicInteger NEXT_IP = new AtomicInteger(1);

    @Autowired MockMvc mockMvc;
    @MockBean RestaurantRepository restaurantRepository;
    @MockBean RestaurantFoodRepository restaurantFoodRepository;
    @MockBean GoongClient goongClient;
    @SpyBean IngestionQueue ingestionQueue;

    private static AvailabilityCandidateRow row(long id, String name, double lat, double lng, double dist) {
        return new AvailabilityCandidateRow() {
            public Long getId() { return id; }
            public String getName() { return name; }
            public String getCuisineType() { return null; }
            public Double getLatitude() { return lat; }
            public Double getLongitude() { return lng; }
            public Double getDistanceMeters() { return dist; }
        };
    }

    @BeforeEach
    void setUp() {
        when(restaurantRepository.findAvailabilityCandidates(anyDouble(), anyDouble(), anyDouble(),
            anyDouble(), anyDouble(), anyDouble(), anyDouble(), anyInt())).thenReturn(List.of(
                row(1, "Phở Hòa Pasteur", 10.77620, 106.68920, 1282.0),
                row(47, "Sushi Masa", 10.79051, 106.69247, 1765.8)));
        when(restaurantFoodRepository.findDishNamesByRestaurantIds(anyCollection())).thenReturn(List.<RestaurantDishNameProjection>of());
        when(restaurantRepository.countRestaurantsWithinAreas(anyString(), anyString(), anyString())).thenAnswer(inv -> {
            List<AreaSupplyRow> rows = new ArrayList<>();
            for (long i = 1; i <= 10; i++) {
                long index = i, count = i == 8 ? 3 : 40;       // 8th area in display order: go-vap
                rows.add(new AreaSupplyRow() {
                    public Long getAreaIndex() { return index; }
                    public Long getRestaurantCount() { return count; }
                });
            }
            return rows;
        });
        when(restaurantRepository.findAllById(any())).thenAnswer(inv -> {
            Collection<Long> ids = inv.getArgument(0);
            return List.of(
                Restaurant.builder().id(1L).goongPlaceId("g-1").name("Phở Hòa Pasteur").latitude(10.77620)
                    .longitude(106.68920).avgRating(new BigDecimal("4.50")).reviewCount(4).isActive(true).build(),
                Restaurant.builder().id(47L).goongPlaceId("g-47").name("Sushi Masa").latitude(10.79051)
                    .longitude(106.69247).avgRating(BigDecimal.ZERO).reviewCount(0).isActive(true).build())
                .stream().filter(r -> ids.contains(r.getId())).toList();
        });
    }

    private static MockHttpServletRequestBuilder fresh(MockHttpServletRequestBuilder b) {
        String ip = "10.1.0." + NEXT_IP.getAndIncrement();
        return b.with(r -> { r.setRemoteAddr(ip); return r; });
    }

    // ── /decisions/food ──────────────────────────────────────────────────────

    @Test
    void food_isAnonymous_andReturnsAnAvailableFood() throws Exception {
        String body = mockMvc.perform(fresh(get("/api/v1/decisions/food")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.food.key").value(org.hamcrest.Matchers.oneOf("pho", "sushi")))
            .andExpect(jsonPath("$.data.restaurantCount").value(1))
            .andExpect(jsonPath("$.data.radiusMeters").value(3000))
            .andExpect(jsonPath("$.data.radiusWidened").value(false))
            .andExpect(jsonPath("$.data.candidateFoodCount").value(2))
            .andExpect(jsonPath("$.data.scope.locationSource").value("CITY_CENTER"))
            .andExpect(jsonPath("$.data.decisionId").exists())
            .andExpect(jsonPath("$.data.emptyReason").doesNotExist())
            .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(body).doesNotContain("keywords", "suppressors", "asciiSafe", "evidence", "lucky");
        verify(ingestionQueue, never()).offer(any());
        verifyNoInteractions(goongClient);
    }

    @Test
    void food_withExclusions_neverReturnsAnExcludedFood() throws Exception {
        for (int i = 0; i < 5; i++) {
            mockMvc.perform(fresh(get("/api/v1/decisions/food").param("exclude", "pho,pho")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.food.key").value("sushi"))
                .andExpect(jsonPath("$.data.candidateFoodCount").value(1));
        }
        mockMvc.perform(fresh(get("/api/v1/decisions/food").param("exclude", "pho,sushi")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.food").doesNotExist())
            .andExpect(jsonPath("$.data.emptyReason").value("ALL_SHOWN"));
    }

    @Test
    void food_validation() throws Exception {
        mockMvc.perform(fresh(get("/api/v1/decisions/food").param("exclude", "phở"))).andExpect(status().isBadRequest());
        mockMvc.perform(fresh(get("/api/v1/decisions/food").param("kinds", "SNACK"))).andExpect(status().isBadRequest());
        // lat alone, lng alone, radius without a location, an unknown area
        for (String p : new String[]{"lat", "lng", "radius", "area"}) {
            mockMvc.perform(fresh(get("/api/v1/decisions/food").param(p, "1"))).andExpect(status().isBadRequest());
        }
    }

    // ── /decisions/today?food= ───────────────────────────────────────────────

    @Test
    void today_withFood_returnsAClassifiedRestaurantWithThatFood() throws Exception {
        mockMvc.perform(fresh(get("/api/v1/decisions/today").param("food", "pho")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.restaurant.id").value(1))
            .andExpect(jsonPath("$.data.restaurant.foodKeys[0]").value("pho"))
            .andExpect(jsonPath("$.data.restaurant.category").value("noodle"))
            .andExpect(jsonPath("$.data.food.key").value("pho"))
            .andExpect(jsonPath("$.data.food.label").value("Phở"))
            .andExpect(jsonPath("$.data.candidateCount").value(1))
            .andExpect(jsonPath("$.data.locationSource").value("CITY_CENTER"))
            .andExpect(jsonPath("$.data.distanceMeters").doesNotExist())
            .andExpect(jsonPath("$.data.radiusWidened").value(false))
            .andExpect(jsonPath("$.data.reasons[1].code").value("FOOD_NAME"));
        verify(ingestionQueue, never()).offer(any());
        verifyNoInteractions(goongClient);
    }

    @Test
    void today_withFood_noRestaurant_isNullWithReason_neverAnotherFood() throws Exception {
        mockMvc.perform(fresh(get("/api/v1/decisions/today").param("food", "lau")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.restaurant").doesNotExist())
            .andExpect(jsonPath("$.data.food.key").value("lau"))
            .andExpect(jsonPath("$.data.emptyReason").value("NO_FOOD_IN_SCOPE"))
            .andExpect(jsonPath("$.data.radiusMeters").value(5000))
            .andExpect(jsonPath("$.data.radiusWidened").value(true));
        mockMvc.perform(fresh(get("/api/v1/decisions/today").param("food", "pho").param("exclude", "1")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.restaurant").doesNotExist())
            .andExpect(jsonPath("$.data.emptyReason").value("ALL_SHOWN"));
    }

    @Test
    void today_withFood_validation() throws Exception {
        mockMvc.perform(fresh(get("/api/v1/decisions/today").param("food", "xyz"))).andExpect(status().isBadRequest());
        mockMvc.perform(fresh(get("/api/v1/decisions/today").param("food", "tra"))).andExpect(status().isBadRequest());
        mockMvc.perform(fresh(get("/api/v1/decisions/today").param("food", "pho").param("mood", "mon-nuoc")))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.message").value("food and mood cannot be combined."));
        mockMvc.perform(fresh(get("/api/v1/decisions/today").param("food", "pho").param("lat", "10.7")))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.message").value("lat and lng must be provided together."));
        mockMvc.perform(fresh(get("/api/v1/decisions/today").param("food", "pho").param("exclude", "x")))
            .andExpect(status().isBadRequest());
    }

    // ── Rate limiting: one decision bucket for /today and /food ──────────────

    @Test
    void burstThen429_withRetryAfter_sharedWithToday() throws Exception {
        String ip = "10.2.2.2";
        int ok = 0;
        for (int i = 0; i < 20; i++) {
            int code = mockMvc.perform(get("/api/v1/decisions/food").with(r -> { r.setRemoteAddr(ip); return r; }))
                .andReturn().getResponse().getStatus();
            if (code == 200) ok++;
        }
        assertThat(ok).isEqualTo(20);   // default burst

        int code = 200;
        for (int i = 0; i < 5 && code == 200; i++) {   // a slow machine may earn a refill or two
            code = mockMvc.perform(get("/api/v1/decisions/food").with(r -> { r.setRemoteAddr(ip); return r; }))
                .andReturn().getResponse().getStatus();
        }
        assertThat(code).isEqualTo(429);
        mockMvc.perform(get("/api/v1/decisions/food").header("Origin", "http://localhost:5173")
                .with(r -> { r.setRemoteAddr(ip); return r; }))
            .andExpect(status().isTooManyRequests())
            .andExpect(header().exists("Retry-After"))
            // the cross-origin frontend can read it (CORS exposes it)
            .andExpect(header().string("Access-Control-Expose-Headers", org.hamcrest.Matchers.containsString("Retry-After")));
        // the same client's restaurant decisions share the bucket
        mockMvc.perform(get("/api/v1/decisions/today").param("food", "pho").with(r -> { r.setRemoteAddr(ip); return r; }))
            .andExpect(status().isTooManyRequests());
        // other clients are unaffected
        mockMvc.perform(fresh(get("/api/v1/decisions/food"))).andExpect(status().isOk());
    }

    // ── Scopes (USER / AREA / CITY_CENTER) ───────────────────────────────────

    private static final String USER_LAT = "10.7768", USER_LNG = "106.6900";

    @Test
    void userScope_food_and_restaurant_realDistance_noCoordinatesInTheResponse() throws Exception {
        String food = mockMvc.perform(fresh(get("/api/v1/decisions/food").param("lat", USER_LAT).param("lng", USER_LNG)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.scope.locationSource").value("USER"))
            .andExpect(jsonPath("$.data.scope.label").value("Gần bạn"))
            .andExpect(jsonPath("$.data.scope.radiusMeters").value(1000))
            .andExpect(jsonPath("$.data.scope.area").doesNotExist())
            .andExpect(jsonPath("$.data.radiusMeters").value(3000))       // both rows are > 1 km by PostGIS → widened
            .andExpect(jsonPath("$.data.radiusWidened").value(true))
            .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(food).doesNotContain(USER_LAT, USER_LNG, "\"lat\"", "\"lng\"");

        String today = mockMvc.perform(fresh(get("/api/v1/decisions/today").param("food", "pho")
                .param("lat", USER_LAT).param("lng", USER_LNG)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.restaurant.id").value(1))
            .andExpect(jsonPath("$.data.locationSource").value("USER"))
            .andExpect(jsonPath("$.data.distanceMeters").value(110))     // real distance from the user
            .andExpect(jsonPath("$.data.reasons[0].code").value("DISTANCE"))
            .andExpect(jsonPath("$.data.reasons[0].label").value("Cách bạn 110 m"))
            .andExpect(jsonPath("$.data.area").doesNotExist())
            .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(today).doesNotContain(USER_LAT, USER_LNG);
    }

    @Test
    void areaScope_namesTheArea_noDistance_noCentre() throws Exception {
        String available = mockMvc.perform(fresh(get("/api/v1/decisions/foods/available").param("area", "quan-1")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.scope.locationSource").value("AREA"))
            .andExpect(jsonPath("$.data.scope.area.key").value("quan-1"))
            .andExpect(jsonPath("$.data.scope.area.label").value("Quận 1"))
            .andExpect(jsonPath("$.data.scope.label").value("Quanh Quận 1"))
            .andExpect(jsonPath("$.data.scope.radiusMeters").value(2000))
            .andExpect(jsonPath("$.data.foods[0].key").value("pho"))    // Phở Hòa Pasteur, 1.2 km from the centre
            .andExpect(jsonPath("$.data.foods[0].radiusWidened").value(false))
            .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(available).doesNotContain("10.7737", "106.6999", "center", "\"lat\"");

        mockMvc.perform(fresh(get("/api/v1/decisions/food").param("area", "quan-1")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.food.key").value("pho"))
            .andExpect(jsonPath("$.data.radiusMeters").value(2000))
            .andExpect(jsonPath("$.data.scope.locationSource").value("AREA"));

        String today = mockMvc.perform(fresh(get("/api/v1/decisions/today").param("food", "pho").param("area", "quan-1")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.restaurant.id").value(1))
            .andExpect(jsonPath("$.data.locationSource").value("AREA"))
            .andExpect(jsonPath("$.data.area.key").value("quan-1"))
            .andExpect(jsonPath("$.data.distanceMeters").doesNotExist())
            .andExpect(jsonPath("$.data.radiusWidened").value(false))
            .andExpect(jsonPath("$.data.reasons[0].label").value("Quanh Quận 1"))
            .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(today).doesNotContain("Cách bạn", "Gần bạn", "DISTANCE", "10.7737", "106.6999");

        // nothing within an area's own radius → empty; never widened, never another scope
        mockMvc.perform(fresh(get("/api/v1/decisions/today").param("food", "sushi").param("area", "binh-thanh")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.restaurant").doesNotExist())
            .andExpect(jsonPath("$.data.emptyReason").value("NO_FOOD_IN_SCOPE"))
            .andExpect(jsonPath("$.data.radiusMeters").value(2500))
            .andExpect(jsonPath("$.data.radiusWidened").value(false))
            .andExpect(jsonPath("$.data.locationSource").value("AREA"));
    }

    @Test
    void invalidScopes_are400_onEveryEndpoint() throws Exception {
        String[][] invalid = {
            {"lat", "10.7"},                                   // lat without lng
            {"lng", "106.7"},                                  // lng without lat
            {"lat", "91", "lng", "106.7"},                     // out of range
            {"lat", "10.7", "lng", "181"},
            {"lat", "x", "lng", "106.7"},
            {"lat", "10.7", "lng", "106.7", "radius", "0"},    // radius out of range
            {"lat", "10.7", "lng", "106.7", "radius", "5001"},
            {"radius", "1000"},                                // radius without a location
            {"area", "quan-1", "radius", "1000"},              // radius with an area
            {"area", "quan-1", "lat", "10.7", "lng", "106.7"}, // area with a location
            {"area", "quan-99"},                               // unknown area
            {"area", "go-vap"},                                // known, but too few restaurants
        };
        for (String path : new String[]{"/api/v1/decisions/foods/available", "/api/v1/decisions/food", "/api/v1/decisions/today",
                "/api/v1/decisions/foods/pho/restaurants"}) {
            for (String[] params : invalid) {
                MockHttpServletRequestBuilder request = get(path);
                if (path.endsWith("today")) request = request.param("food", "pho");
                for (int i = 0; i < params.length; i += 2) request = request.param(params[i], params[i + 1]);
                mockMvc.perform(fresh(request)).andExpect(status().isBadRequest());
            }
        }
        mockMvc.perform(fresh(get("/api/v1/decisions/food").param("area", "go-vap")))
            .andExpect(jsonPath("$.message").value("Area 'go-vap' is not available right now; choose another area."));
        verify(restaurantRepository, never()).findAvailabilityCandidates(anyDouble(), anyDouble(), anyDouble(),
            anyDouble(), anyDouble(), anyDouble(), anyDouble(), anyInt());
    }

    // ── /decisions/foods/{key}/restaurants (FoodPage list) ───────────────────

    private static final String PHO_LIST = "/api/v1/decisions/foods/pho/restaurants";

    // ── Count semantics: every count says whether it is exact ────────────────

    @Test
    void counts_areExact_andSaySo_whenTheRowsReadStayUnderTheCap() throws Exception {
        mockMvc.perform(fresh(get("/api/v1/decisions/foods/available")))
            .andExpect(jsonPath("$.data.foods[0].count").value(1))
            .andExpect(jsonPath("$.data.foods[0].countCapped").value(false));
        mockMvc.perform(fresh(get(PHO_LIST)))
            .andExpect(jsonPath("$.data.totalElements").value(1))
            .andExpect(jsonPath("$.data.totalCapped").value(false));
        mockMvc.perform(fresh(get("/api/v1/decisions/food")))
            .andExpect(jsonPath("$.data.restaurantCount").value(1))
            .andExpect(jsonPath("$.data.restaurantCountCapped").value(false));
    }

    @Test
    void counts_fromACappedRead_areFlaggedAsLowerBounds_onEveryEndpoint() throws Exception {
        int cap = 2000;   // FoodAvailabilityService.CANDIDATE_SAFETY_CAP
        List<AvailabilityCandidateRow> rows = new ArrayList<>();
        for (int i = 0; i < 10; i++) rows.add(row(10_000 + i, "Phở số " + i, 10.7769, 106.7009, 100.0));
        for (int i = 0; i < cap - 10; i++) rows.add(row(20_000 + i, "Cà phê số " + i, 10.7769, 106.7009, 100.0));
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

        mockMvc.perform(fresh(get("/api/v1/decisions/foods/available")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.foods[?(@.key=='ca-phe')].count").value(cap - 10))
            .andExpect(jsonPath("$.data.foods[?(@.key=='ca-phe')].countCapped").value(true))
            .andExpect(jsonPath("$.data.foods[?(@.key=='pho')].count").value(10))
            .andExpect(jsonPath("$.data.foods[?(@.key=='pho')].countCapped").value(true));
        mockMvc.perform(fresh(get(PHO_LIST).param("size", "4").param("page", "2")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.totalElements").value(10))
            .andExpect(jsonPath("$.data.totalCapped").value(true))
            .andExpect(jsonPath("$.data.items.length()").value(2));      // the last page of the evaluated rows
        mockMvc.perform(fresh(get("/api/v1/decisions/food")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.food.key").value("pho"))
            .andExpect(jsonPath("$.data.restaurantCount").value(10))
            .andExpect(jsonPath("$.data.restaurantCountCapped").value(true));
        verify(ingestionQueue, never()).offer(any());
        verifyNoInteractions(goongClient);
    }

    @Test
    void foodRestaurants_isAnonymous_evidenceForEveryItem_noDistanceAtTheCentre() throws Exception {
        String body = mockMvc.perform(fresh(get(PHO_LIST)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.food.key").value("pho"))
            .andExpect(jsonPath("$.data.food.label").value("Phở"))
            .andExpect(jsonPath("$.data.totalElements").value(1))
            .andExpect(jsonPath("$.data.page").value(0))
            .andExpect(jsonPath("$.data.size").value(20))
            .andExpect(jsonPath("$.data.scope.locationSource").value("CITY_CENTER"))
            .andExpect(jsonPath("$.data.scope.label").value("Quanh trung tâm TP.HCM"))
            .andExpect(jsonPath("$.data.scope.radiusMeters").value(3000))
            .andExpect(jsonPath("$.data.scope.radiusWidened").value(false))
            .andExpect(jsonPath("$.data.items.length()").value(1))
            .andExpect(jsonPath("$.data.items[0].restaurant.id").value(1))
            .andExpect(jsonPath("$.data.items[0].restaurant.foodKeys[0]").value("pho"))
            .andExpect(jsonPath("$.data.items[0].restaurant.category").value("noodle"))
            .andExpect(jsonPath("$.data.items[0].evidence.source").value("NAME"))
            .andExpect(jsonPath("$.data.items[0].evidence.dishName").doesNotExist())
            .andExpect(jsonPath("$.data.items[0].distanceMeters").doesNotExist())
            .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(body).doesNotContain("keywords", "suppressors", "asciiSafe", "Sushi Masa", "Cách bạn");
        verify(ingestionQueue, never()).offer(any());
        verifyNoInteractions(goongClient);
        verify(restaurantRepository, never()).save(any());
        verify(restaurantRepository, never()).saveAll(any());
    }

    @Test
    void foodRestaurants_unknownOrClassificationOnlyFood_is404_neverAnotherFood() throws Exception {
        for (String key : new String[]{"xyz", "tra", "PHO", "phở", "available"}) {
            mockMvc.perform(fresh(get("/api/v1/decisions/foods/" + key + "/restaurants")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.data").doesNotExist());
        }
        verify(restaurantRepository, never()).findAvailabilityCandidates(anyDouble(), anyDouble(), anyDouble(),
            anyDouble(), anyDouble(), anyDouble(), anyDouble(), anyInt());
    }

    @Test
    void foodRestaurants_pageAndSizeValidation() throws Exception {
        for (String[] p : new String[][]{{"size", "0"}, {"size", "51"}, {"size", "x"}, {"size", "-1"},
                                         {"page", "-1"}, {"page", "x"}, {"page", "1.5"}}) {
            mockMvc.perform(fresh(get(PHO_LIST).param(p[0], p[1]))).andExpect(status().isBadRequest());
        }
        mockMvc.perform(fresh(get(PHO_LIST).param("size", "1"))).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.size").value(1));
        mockMvc.perform(fresh(get(PHO_LIST).param("size", "50"))).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.size").value(50));
        mockMvc.perform(fresh(get(PHO_LIST).param("page", "7"))).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.totalElements").value(1))       // the whole set, not the page
            .andExpect(jsonPath("$.data.items.length()").value(0));
    }

    @Test
    void foodRestaurants_sameRequest_sameBody() throws Exception {
        String first = null;
        for (int i = 0; i < 5; i++) {
            String body = new com.fasterxml.jackson.databind.ObjectMapper().readTree(mockMvc.perform(fresh(get("/api/v1/decisions/foods/sushi/restaurants")))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8)).path("data").toString();   // the envelope timestamp differs
            if (first == null) first = body;
            assertThat(body).isEqualTo(first);
        }
    }

    @Test
    void foodRestaurants_totalElements_equalsTheThemGiCount_inEveryScope() throws Exception {
        com.fasterxml.jackson.databind.ObjectMapper json = new com.fasterxml.jackson.databind.ObjectMapper();
        String[][] scopes = {{}, {"area", "quan-1"}, {"lat", USER_LAT, "lng", USER_LNG}, {"lat", USER_LAT, "lng", USER_LNG, "radius", "3000"}};
        int checked = 0;
        for (String[] scope : scopes) {
            MockHttpServletRequestBuilder available = get("/api/v1/decisions/foods/available");
            for (int i = 0; i < scope.length; i += 2) available = available.param(scope[i], scope[i + 1]);
            var foods = json.readTree(mockMvc.perform(fresh(available)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8)).path("data").path("foods");
            assertThat(foods.size()).isPositive();
            for (var f : foods) {
                MockHttpServletRequestBuilder list = get("/api/v1/decisions/foods/" + f.path("key").asText() + "/restaurants");
                for (int i = 0; i < scope.length; i += 2) list = list.param(scope[i], scope[i + 1]);
                mockMvc.perform(fresh(list)).andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.totalElements").value(f.path("count").asInt()))
                    .andExpect(jsonPath("$.data.scope.radiusMeters").value(f.path("radiusMeters").asInt()))
                    .andExpect(jsonPath("$.data.scope.radiusWidened").value(f.path("radiusWidened").asBoolean()));
                checked++;
            }
        }
        assertThat(checked).isGreaterThanOrEqualTo(4);
    }

    @Test
    void foodRestaurants_userScope_realDistance_areaScope_none_noCoordinatesInTheResponse() throws Exception {
        String user = mockMvc.perform(fresh(get(PHO_LIST).param("lat", USER_LAT).param("lng", USER_LNG)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.scope.locationSource").value("USER"))
            .andExpect(jsonPath("$.data.scope.label").value("Gần bạn"))
            .andExpect(jsonPath("$.data.items[0].distanceMeters").value(110))
            .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(user).doesNotContain(USER_LAT, USER_LNG);

        String area = mockMvc.perform(fresh(get(PHO_LIST).param("area", "quan-1")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.scope.locationSource").value("AREA"))
            .andExpect(jsonPath("$.data.scope.area.key").value("quan-1"))
            .andExpect(jsonPath("$.data.scope.label").value("Quanh Quận 1"))
            .andExpect(jsonPath("$.data.scope.radiusMeters").value(2000))
            .andExpect(jsonPath("$.data.items[0].restaurant.id").value(1))
            .andExpect(jsonPath("$.data.items[0].distanceMeters").doesNotExist())
            .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(area).doesNotContain("10.7737", "106.6999", "distanceMeters", "Cách bạn", "Gần bạn");

        // nothing within the area's own radius: empty, never widened, never another food
        mockMvc.perform(fresh(get("/api/v1/decisions/foods/sushi/restaurants").param("area", "binh-thanh")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.totalElements").value(0))
            .andExpect(jsonPath("$.data.items.length()").value(0))
            .andExpect(jsonPath("$.data.food.key").value("sushi"))
            .andExpect(jsonPath("$.data.scope.radiusWidened").value(false));
    }

    @Test
    void foodRestaurants_browseBucket_60Burst_then429_decisionBucketUntouched() throws Exception {
        String ip = "10.3.3.3";
        int ok = 0;
        for (int i = 0; i < 60; i++) {
            int code = mockMvc.perform(get(PHO_LIST).with(r -> { r.setRemoteAddr(ip); return r; }))
                .andReturn().getResponse().getStatus();
            if (code == 200) ok++;
        }
        assertThat(ok).isEqualTo(60);   // the browse burst, not the decision bucket's 20

        int code = 200;
        for (int i = 0; i < 5 && code == 200; i++) {   // a slow machine may earn a refill or two
            code = mockMvc.perform(get(PHO_LIST).with(r -> { r.setRemoteAddr(ip); return r; }))
                .andReturn().getResponse().getStatus();
        }
        assertThat(code).isEqualTo(429);
        mockMvc.perform(get(PHO_LIST).header("Origin", "http://localhost:5173").with(r -> { r.setRemoteAddr(ip); return r; }))
            .andExpect(status().isTooManyRequests())
            .andExpect(header().exists("Retry-After"))
            .andExpect(header().string("Access-Control-Expose-Headers", org.hamcrest.Matchers.containsString("Retry-After")));
        // the same client can still ask the cà mèn
        mockMvc.perform(get("/api/v1/decisions/food").with(r -> { r.setRemoteAddr(ip); return r; }))
            .andExpect(status().isOk());
    }

    @Test
    void coordinates_neverReachTheLogs_atInfoOrAbove_andNothingIsStored(CapturedOutput output) throws Exception {
        String lat = "10.781234", lng = "106.701234";
        mockMvc.perform(fresh(get("/api/v1/decisions/foods/available").param("lat", lat).param("lng", lng)))
            .andExpect(status().isOk());
        mockMvc.perform(fresh(get("/api/v1/decisions/food").param("lat", lat).param("lng", lng).param("radius", "2000")))
            .andExpect(status().isOk());
        mockMvc.perform(fresh(get("/api/v1/decisions/today").param("food", "pho").param("lat", lat).param("lng", lng)))
            .andExpect(status().isOk());
        mockMvc.perform(fresh(get("/api/v1/decisions/today").param("lat", lat).param("lng", lng)))
            .andExpect(status().isOk());
        mockMvc.perform(fresh(get(PHO_LIST).param("lat", lat).param("lng", lng)))
            .andExpect(status().isOk());
        mockMvc.perform(fresh(get("/api/v1/decisions/today").param("lat", lat)))         // a rejected one too
            .andExpect(status().isBadRequest());

        assertThat(output.getAll()).doesNotContain(lat, lng, "10.78123", "106.70123");
        verify(restaurantRepository, never()).save(any());
        verify(restaurantRepository, never()).saveAll(any());
        verify(ingestionQueue, never()).offer(any());
        verifyNoInteractions(goongClient);
    }
}
