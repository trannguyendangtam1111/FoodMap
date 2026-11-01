package com.foodmap.restaurant.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;
import lombok.Getter;

import java.math.BigDecimal;
import java.util.List;

/**
 * Lightweight projection returned by viewport, nearby, and search endpoints.
 * Contains everything the map marker and sidebar card need — no heavy relations.
 */
@Getter
@Builder
public class RestaurantSummaryDTO {
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
}
