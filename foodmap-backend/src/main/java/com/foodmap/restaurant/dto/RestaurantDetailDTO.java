package com.foodmap.restaurant.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;
import lombok.Getter;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * Full restaurant detail returned by GET /restaurants/{id}.
 * Includes everything in RestaurantSummaryDTO plus relations
 * (menu items, opening hours, phone, website).
 */
@Getter
@Builder
public class RestaurantDetailDTO {
    // ── Core fields (same as summary) ─────────────────────
    private Long id;
    private String name;
    private String address;
    private Double latitude;
    private Double longitude;
    private BigDecimal avgRating;
    private Integer reviewCount;
    private Integer priceLevel;
    private String cuisineType;
    private List<String> photos;

    // ── Server food classification (catalog + name + FoodMap menu) ────────
    /** Matched food keys, primary first. Omitted when none. */
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    private List<String> foodKeys;
    /** CategoryArt key; omitted when unknown (clients show "other"). */
    private String category;

    // ── Detail-only fields ────────────────────────────────
    private String phone;
    private String website;
    private Map<String, String> openingHours;
    private List<RestaurantFoodDTO> restaurantFoods;
}
