package com.foodmap.food.match;

import com.foodmap.restaurant.domain.Restaurant;
import com.foodmap.restaurant.dto.RestaurantDishNameProjection;
import com.foodmap.restaurant.repository.RestaurantFoodRepository;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Classifies restaurants for API responses. Menus are read in ONE batched
 * query per list ({@link RestaurantFoodRepository#findDishNamesByRestaurantIds}),
 * never one query per restaurant. Read-only; nothing is stored.
 */
@Component
public class FoodClassifier {

    private final FoodMatcher matcher;
    private final RestaurantFoodRepository restaurantFoodRepository;

    public FoodClassifier(FoodMatcher matcher, RestaurantFoodRepository restaurantFoodRepository) {
        this.matcher = matcher;
        this.restaurantFoodRepository = restaurantFoodRepository;
    }

    public FoodMatcher matcher() {
        return matcher;
    }

    /** Dish names per restaurant id, in one query (none for an empty input). */
    public Map<Long, List<String>> menusOf(Collection<Long> restaurantIds) {
        List<Long> ids = restaurantIds.stream().filter(Objects::nonNull).distinct().toList();
        if (ids.isEmpty()) return Map.of();
        return restaurantFoodRepository.findDishNamesByRestaurantIds(ids).stream()
            .collect(Collectors.groupingBy(RestaurantDishNameProjection::getRestaurantId,
                Collectors.mapping(RestaurantDishNameProjection::getDishName, Collectors.toList())));
    }

    /** Classifies a list with one menu query in total. */
    public Map<Long, FoodClassification> classifyAll(Collection<Restaurant> restaurants) {
        Map<Long, List<String>> menus = menusOf(restaurants.stream().map(Restaurant::getId).toList());
        return classifyAll(restaurants, menus);
    }

    /** Classifies a list using menus the caller already loaded (no query). */
    public Map<Long, FoodClassification> classifyAll(Collection<Restaurant> restaurants, Map<Long, List<String>> menus) {
        Map<Long, FoodClassification> out = new HashMap<>();
        for (Restaurant r : restaurants) {
            out.put(r.getId(), classify(r.getName(), r.getCuisineType(), menus.getOrDefault(r.getId(), List.of())));
        }
        return out;
    }

    public FoodClassification classify(String name, String cuisineType, List<String> dishNames) {
        return matcher.classify(name, cuisineType, dishNames);
    }
}
