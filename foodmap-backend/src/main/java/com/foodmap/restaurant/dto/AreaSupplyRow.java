package com.foodmap.restaurant.dto;

/**
 * Native-query projection: how many active, named, located restaurants lie
 * inside one discovery area (1-based position in the request). Server-internal.
 */
public interface AreaSupplyRow {
    Long getAreaIndex();
    Long getRestaurantCount();
}
