package com.foodmap.restaurant.service;

import com.foodmap.common.dto.PageResponse;
import com.foodmap.common.exception.ResourceNotFoundException;
import com.foodmap.food.match.FoodClassification;
import com.foodmap.food.match.FoodClassifier;
import com.foodmap.restaurant.dto.RestaurantDetailDTO;
import com.foodmap.restaurant.dto.RestaurantSummaryDTO;
import com.foodmap.restaurant.mapper.RestaurantMapper;
import com.foodmap.restaurant.repository.RestaurantRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

/**
 * Non-spatial restaurant lookups.
 *
 * Viewport, nearby, and search live on DiscoveryService now — they share
 * a common "read from local DB + enqueue stale cells" shape that doesn't
 * apply to detail or by-food queries.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class RestaurantService {

    private final RestaurantRepository restaurantRepository;
    private final RestaurantMapper restaurantMapper;
    private final FoodClassifier foodClassifier;

    /**
     * Full restaurant detail — menu, opening hours, phone, website.
     * Backs the restaurant detail page.
     */
    public RestaurantDetailDTO getById(Long id) {
        var restaurant = restaurantRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Restaurant not found: " + id));
        List<String> menu = restaurant.getRestaurantFoods().stream().map(rf -> rf.getFood().getName()).toList();
        return restaurantMapper.toDetail(restaurant,
            foodClassifier.classify(restaurant.getName(), restaurant.getCuisineType(), menu));
    }

    /**
     * All restaurants that serve a specific food (dish-filter mode on the map).
     * Triggered when a user picks a dish from the SearchBar.
     */
    public PageResponse<RestaurantSummaryDTO> getByFoodId(Long foodId, int page, int size) {
        var pageable = PageRequest.of(page, Math.min(size, 50),
                Sort.by("avgRating").descending());
        var resultPage = restaurantRepository.findByFoodId(foodId, pageable);
        Map<Long, FoodClassification> food = foodClassifier.classifyAll(resultPage.getContent());
        return PageResponse.of(
                resultPage.getContent().stream().map(r -> restaurantMapper.toSummary(r, food.get(r.getId()))).toList(),
                resultPage
        );
    }
}
