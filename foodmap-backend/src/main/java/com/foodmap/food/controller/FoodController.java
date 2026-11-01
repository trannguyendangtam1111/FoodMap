package com.foodmap.food.controller;

import com.foodmap.common.dto.ApiResponse;
import com.foodmap.common.dto.PageResponse;
import com.foodmap.food.dto.FoodDTO;
import com.foodmap.food.dto.FoodSummaryDTO;
import com.foodmap.food.service.FoodService;
import com.foodmap.restaurant.dto.RestaurantSummaryDTO;
import com.foodmap.restaurant.service.RestaurantService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/foods")
@RequiredArgsConstructor
@Tag(name = "Foods", description = "Food search and detail endpoints")
public class FoodController {

    private final FoodService foodService;
    private final RestaurantService restaurantService;

    /**
     * GET /api/v1/foods/search?query=pho&page=0&size=8
     *
     * Autocomplete endpoint — returns foods whose name contains the query string.
     * Ordered by popularityScore descending so trending dishes appear first.
     * Used by the SearchBar to populate the "Dishes" section of the dropdown.
     */
    @GetMapping("/search")
    @Operation(summary = "Search foods by name")
    public ResponseEntity<ApiResponse<PageResponse<FoodSummaryDTO>>> search(
            @RequestParam String query,
            @RequestParam(defaultValue = "0")  int page,
            @RequestParam(defaultValue = "8")  int size
    ) {
        return ResponseEntity.ok(ApiResponse.ok(foodService.search(query, page, size)));
    }

    /**
     * GET /api/v1/foods/{id}
     *
     * Returns full food detail including description and all tags.
     */
    @GetMapping("/{id}")
    @Operation(summary = "Get food by ID")
    public ResponseEntity<ApiResponse<FoodDTO>> getById(@PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.ok(foodService.getById(id)));
    }

    /**
     * GET /api/v1/foods/slug/{slug}
     *
     * Alternative lookup by the URL-safe slug (e.g. "pho-bo-tai").
     * Useful for SEO-friendly deep links.
     */
    @GetMapping("/slug/{slug}")
    @Operation(summary = "Get food by slug")
    public ResponseEntity<ApiResponse<FoodDTO>> getBySlug(@PathVariable String slug) {
        return ResponseEntity.ok(ApiResponse.ok(foodService.getBySlug(slug)));
    }

    /**
     * GET /api/v1/foods/{id}/restaurants?page=0&size=20
     *
     * Dish-filter mode: returns all active restaurants that serve this food,
     * ordered by rating. This is what fires when the user clicks a dish in the
     * search dropdown — the map and sidebar update to show only these restaurants.
     */
    @GetMapping("/{id}/restaurants")
    @Operation(summary = "Get restaurants serving a specific food")
    public ResponseEntity<ApiResponse<PageResponse<RestaurantSummaryDTO>>> getRestaurants(
            @PathVariable Long id,
            @RequestParam(defaultValue = "0")  int page,
            @RequestParam(defaultValue = "50") int size
    ) {
        return ResponseEntity.ok(ApiResponse.ok(restaurantService.getByFoodId(id, page, size)));
    }
}
