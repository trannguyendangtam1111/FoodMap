package com.foodmap.restaurant.dto;

import com.foodmap.food.dto.FoodSummaryDTO;
import lombok.Builder;
import lombok.Getter;

import java.math.BigDecimal;

/**
 * Represents a food item offered at a specific restaurant,
 * including the menu price and whether it's a signature dish.
 */
@Getter
@Builder
public class RestaurantFoodDTO {
    private Long id;
    private FoodSummaryDTO food;
    private BigDecimal price;
    private boolean signature;
}
