package com.foodmap.food.match;

import com.foodmap.catalog.FoodCatalogLoader;
import com.foodmap.restaurant.domain.Restaurant;
import com.foodmap.restaurant.dto.RestaurantDishNameProjection;
import com.foodmap.restaurant.repository.RestaurantFoodRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Collection;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/** Classification of a list costs one menu query in total — never one per restaurant. */
class FoodClassifierTest {

    private RestaurantFoodRepository repo;
    private FoodClassifier classifier;

    @BeforeEach
    void setUp() {
        repo = mock(RestaurantFoodRepository.class);
        classifier = new FoodClassifier(new FoodMatcher(FoodCatalogLoader.loadFromClasspath()), repo);
    }

    private static Restaurant restaurant(long id, String name, String cuisine) {
        Restaurant r = new Restaurant();
        r.setId(id);
        r.setName(name);
        r.setCuisineType(cuisine);
        return r;
    }

    private static RestaurantDishNameProjection dish(long id, String name) {
        return new RestaurantDishNameProjection() {
            public Long getRestaurantId() { return id; }
            public String getDishName() { return name; }
        };
    }

    @Test
    void classifyAll_usesOneBatchedMenuQuery() {
        // real rows: two seed restaurants with menus, one Goong row without
        List<Restaurant> rows = List.of(
            restaurant(1, "Phở Hòa Pasteur", "Vietnamese"),
            restaurant(9, "Nhà Hàng Ngon", "Vietnamese"),
            restaurant(27, "Phở", null));
        when(repo.findDishNamesByRestaurantIds(any())).thenReturn(List.of(
            dish(1, "Phở Gà"), dish(9, "Phở Bò Tái"), dish(9, "Bánh Xèo")));

        Map<Long, FoodClassification> out = classifier.classifyAll(rows);

        verify(repo, times(1)).findDishNamesByRestaurantIds(any());
        verifyNoMoreInteractions(repo);
        assertThat(out.get(1L).foodKeys()).containsExactly("pho");
        assertThat(out.get(9L).foodKeys()).containsExactly("pho", "banh-xeo");
        assertThat(out.get(27L).foodKeys()).containsExactly("pho");
    }

    @Test
    void classifyAll_queriesEachIdOnce() {
        when(repo.findDishNamesByRestaurantIds(any())).thenReturn(List.of());
        classifier.classifyAll(List.of(restaurant(1, "Phở Hòa Pasteur", null), restaurant(1, "Phở Hòa Pasteur", null)));
        verify(repo).findDishNamesByRestaurantIds(org.mockito.ArgumentMatchers.<Collection<Long>>argThat(ids -> ids.size() == 1));
    }

    @Test
    void emptyList_orPreloadedMenus_queryNothing() {
        assertThat(classifier.classifyAll(List.of())).isEmpty();
        Map<Long, FoodClassification> out = classifier.classifyAll(
            List.of(restaurant(9, "Nhà Hàng Ngon", "Vietnamese")), Map.of(9L, List.of("Phở Bò Tái")));
        assertThat(out.get(9L).foodKeys()).containsExactly("pho");
        verifyNoInteractions(repo);
    }
}
