package com.foodmap.restaurant.dto;

/**
 * Spring Data interface projection: one (restaurant, dish name) pair from a
 * restaurant's FoodMap menu. Used by the decision service to check moods
 * against menus for a whole candidate batch in a single query.
 * Never returned over the wire.
 */
public interface RestaurantDishNameProjection {
    Long getRestaurantId();
    String getDishName();
}
