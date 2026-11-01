package com.foodmap.restaurant.search;

import com.foodmap.catalog.FoodCatalogLoader;
import com.foodmap.common.dto.PageResponse;
import com.foodmap.common.exception.BadRequestException;
import com.foodmap.config.properties.SearchProperties;
import com.foodmap.food.mapper.FoodMapper;
import com.foodmap.food.match.FoodClassifier;
import com.foodmap.food.match.FoodMatcher;
import com.foodmap.restaurant.domain.Restaurant;
import com.foodmap.restaurant.dto.RestaurantDishNameProjection;
import com.foodmap.restaurant.dto.RestaurantSummaryDTO;
import com.foodmap.restaurant.dto.SearchCandidateRow;
import com.foodmap.restaurant.mapper.RestaurantMapper;
import com.foodmap.restaurant.repository.RestaurantFoodRepository;
import com.foodmap.restaurant.repository.RestaurantRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Transactional;

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

class RestaurantSearchServiceTest {

    private RestaurantRepository restaurantRepository;
    private RestaurantFoodRepository restaurantFoodRepository;
    private RestaurantSearchService service;

    @BeforeEach
    void setUp() {
        restaurantRepository = mock(RestaurantRepository.class);
        restaurantFoodRepository = mock(RestaurantFoodRepository.class);
        SearchProperties props = new SearchProperties();
        props.setCandidateCap(300);
        FoodClassifier classifier = new FoodClassifier(
            new FoodMatcher(FoodCatalogLoader.loadFromClasspath()), restaurantFoodRepository);
        service = new RestaurantSearchService(restaurantRepository, classifier,
            new RestaurantMapper(mock(FoodMapper.class)), props);
    }

    // Real rows (dev DB snapshot 2026-10-03)
    private static SearchCandidateRow row(long id, String name, String address, String rating, int reviews,
                                          double lat, double lng) {
        return new SearchCandidateRow() {
            public Long getId() { return id; }
            public String getName() { return name; }
            public String getAddress() { return address; }
            public BigDecimal getAvgRating() { return new BigDecimal(rating); }
            public Integer getReviewCount() { return reviews; }
            public Double getLatitude() { return lat; }
            public Double getLongitude() { return lng; }
            public Double getNameSimilarity() { return 0.0; }
        };
    }

    private static final SearchCandidateRow PHO_HOA = row(1, "Phở Hòa Pasteur", "260C Pasteur, Phường 8, Quận 3, TP.HCM", "4.50", 4, 10.77620, 106.68920);
    private static final SearchCandidateRow NGON = row(9, "Nhà Hàng Ngon", "160 Pasteur, Phường Võ Thị Sáu, Quận 3, TP.HCM", "4.33", 3, 10.77790, 106.69520);
    private static final SearchCandidateRow PHO_26 = row(26, "Phở", "Phở, 161D/36, Phường 3, Quận 11, Thành Phố Hồ Chí Minh", "0.00", 0, 10.76265, 106.64040);
    private static final SearchCandidateRow CAFE_PHO = row(103, "Cafe Phố", "Cafe Phố, 213F Nguyễn Văn Cừ, Phường 3, Quận 5, Hồ Chí Minh", "0.00", 0, 10.76074, 106.68331);

    private static RestaurantDishNameProjection dish(long restaurantId, String name) {
        return new RestaurantDishNameProjection() {
            public Long getRestaurantId() { return restaurantId; }
            public String getDishName() { return name; }
        };
    }

    private static Restaurant entity(SearchCandidateRow r) {
        Restaurant e = new Restaurant();
        e.setId(r.getId());
        e.setName(r.getName());
        e.setAddress(r.getAddress());
        e.setLatitude(r.getLatitude());
        e.setLongitude(r.getLongitude());
        e.setAvgRating(r.getAvgRating());
        e.setReviewCount(r.getReviewCount());
        return e;
    }

    private void stage1Returns(SearchCandidateRow... rows) {
        when(restaurantRepository.findSearchCandidates(anyString(), anyString(), anyString(), anyInt()))
            .thenReturn(List.of(rows));
        when(restaurantRepository.findFoodSearchCandidates(anyString(), anyString(), anyInt())).thenReturn(List.of());
        when(restaurantFoodRepository.findDishNamesByRestaurantIds(any()))
            .thenReturn(List.of(dish(9, "Phở Bò Tái"), dish(1, "Phở Gà")));
        when(restaurantRepository.findAllById(any())).thenAnswer(inv -> {
            Collection<Long> ids = inv.getArgument(0);
            return List.of(rows).stream().filter(r -> ids.contains(r.getId())).map(RestaurantSearchServiceTest::entity).toList();
        });
    }

    @Test
    void ranksPagesAndUsesThreeBatchedQueries_withNoWrites() {
        stage1Returns(CAFE_PHO, NGON, PHO_26, PHO_HOA);

        PageResponse<RestaurantSummaryDTO> page = service.search(RestaurantSearchRequest.parse("phở", "0", "2", null, null));

        assertThat(page.getContent()).extracting(RestaurantSummaryDTO::getId).containsExactly(1L, 26L);
        assertThat(page.getTotalElements()).isEqualTo(3);              // Cafe Phố is not a result
        assertThat(page.isLast()).isFalse();

        PageResponse<RestaurantSummaryDTO> next = service.search(RestaurantSearchRequest.parse("phở", "1", "2", null, null));
        assertThat(next.getContent()).extracting(RestaurantSummaryDTO::getId).containsExactly(9L);
        assertThat(next.isLast()).isTrue();

        // per request: 1 candidate query (+1 because "phở" names a catalog food) + 1 menu batch
        // + 1 page load — no N+1, nothing else
        verify(restaurantRepository, times(2)).findSearchCandidates(eq("phở"), eq("%phở%"), eq("phở%"), eq(300));
        verify(restaurantRepository, times(2)).findFoodSearchCandidates(eq("phở"), eq("%pho%"), eq(300));
        verify(restaurantFoodRepository, times(2)).findDishNamesByRestaurantIds(any());
        verify(restaurantRepository, times(2)).findAllById(any());
        verifyNoMoreInteractions(restaurantRepository, restaurantFoodRepository);
    }

    @Test
    void userOrigin_usesTheNearestFirstCandidateQuery() {
        when(restaurantRepository.findSearchCandidatesNear(anyString(), anyString(), anyString(),
                eq(10.7769), eq(106.7009), eq(300))).thenReturn(List.of(PHO_26));
        when(restaurantRepository.findFoodSearchCandidates(anyString(), anyString(), anyInt())).thenReturn(List.of());
        when(restaurantFoodRepository.findDishNamesByRestaurantIds(any())).thenReturn(List.of());
        when(restaurantRepository.findAllById(any())).thenReturn(List.of(entity(PHO_26)));

        service.search(RestaurantSearchRequest.parse("phở", null, null, "10.7769", "106.7009"));

        verify(restaurantRepository).findSearchCandidatesNear(eq("phở"), eq("%phở%"), eq("phở%"),
            eq(10.7769), eq(106.7009), eq(300));
    }

    @Test
    void noCandidates_skipsTheMenuQuery() {
        when(restaurantRepository.findSearchCandidates(anyString(), anyString(), anyString(), anyInt())).thenReturn(List.of());

        PageResponse<RestaurantSummaryDTO> page = service.search(RestaurantSearchRequest.parse("xyzzy", null, null, null, null));

        assertThat(page.getContent()).isEmpty();
        assertThat(page.getTotalElements()).isZero();
        verify(restaurantRepository).findSearchCandidates(anyString(), anyString(), anyString(), anyInt());
        verifyNoMoreInteractions(restaurantRepository, restaurantFoodRepository);
    }

    @Test
    void queryWithoutWords_neverTouchesTheDatabase() {
        PageResponse<RestaurantSummaryDTO> page = service.search(RestaurantSearchRequest.parse("%", null, null, null, null));
        assertThat(page.getContent()).isEmpty();
        verifyNoMoreInteractions(restaurantRepository, restaurantFoodRepository);
    }

    @Test
    void likeWildcardsInTheQueryAreEscaped() {
        assertThat(RestaurantSearchService.escapeLike("50%_off\\")).isEqualTo("50\\%\\_off\\\\");
    }

    @Test
    void hasNoIngestionOrProviderDependencies_andRunsReadOnly() throws Exception {
        List<String> forbidden = List.of("IngestionQueue", "IngestionWorker", "DiscoveryService",
            "RestaurantSyncService", "GoongClient", "ClickTrackingService", "CellCoverageService",
            "ViewportEnqueueGate");
        for (Field f : RestaurantSearchService.class.getDeclaredFields()) {
            assertThat(forbidden).doesNotContain(f.getType().getSimpleName());
        }
        Transactional tx = RestaurantSearchService.class.getAnnotation(Transactional.class);
        assertThat(tx).isNotNull();
        assertThat(tx.readOnly()).isTrue();
    }

    // ── Request validation ───────────────────────────────────────────────────

    @Test
    void request_defaultsAndClamping() {
        RestaurantSearchRequest r = RestaurantSearchRequest.parse("  phở  ", null, "500", null, null);
        assertThat(r.query()).isEqualTo("phở");
        assertThat(r.page()).isZero();
        assertThat(r.size()).isEqualTo(20);     // V1 ceiling kept
        assertThat(RestaurantSearchRequest.parse("phở", null, null, null, null).size()).isEqualTo(6);
        assertThat(RestaurantSearchRequest.parse("phở", null, "0", null, null).size()).isEqualTo(1);
        assertThat(r.origin()).isNull();
    }

    @Test
    void request_rejectsMalformedInputWith400() {
        assertThatThrownBy(() -> RestaurantSearchRequest.parse(" ", null, null, null, null)).isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> RestaurantSearchRequest.parse(null, null, null, null, null)).isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> RestaurantSearchRequest.parse("a".repeat(101), null, null, null, null))
            .isInstanceOf(BadRequestException.class).hasMessageContaining("100");
        assertThatThrownBy(() -> RestaurantSearchRequest.parse("phở", "-1", null, null, null)).isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> RestaurantSearchRequest.parse("phở", "x", null, null, null)).isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> RestaurantSearchRequest.parse("phở", null, null, "10.7", null))
            .isInstanceOf(BadRequestException.class).hasMessageContaining("together");
        assertThatThrownBy(() -> RestaurantSearchRequest.parse("phở", null, null, "91", "106")).isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> RestaurantSearchRequest.parse("phở", null, null, "abc", "106")).isInstanceOf(BadRequestException.class);
    }
}
