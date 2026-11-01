package com.foodmap.restaurant.dto;

import lombok.Builder;
import lombok.Getter;

import java.math.BigDecimal;

/**
 * Lightweight map-marker projection returned by /api/v1/places/search.
 *
 * Deliberately smaller than RestaurantSummaryDTO: address and photos
 * are dropped because a marker on a map does not need them — the
 * frontend fetches the full detail only when the user taps a marker.
 *
 * Why a separate DTO instead of reusing RestaurantSummaryDTO?
 *   The search endpoint returns distance per row (computed in PostGIS),
 *   which is not part of the entity. Keeping a search-specific DTO
 *   makes the field "distanceMeters" first-class on the wire instead of
 *   bolted onto an entity-shaped response.
 */
@Getter
@Builder
public class PlaceMarkerDTO {
    private Long id;
    private String name;
    private Double latitude;
    private Double longitude;
    private BigDecimal avgRating;
    private Integer reviewCount;
    private Integer priceLevel;
    private String cuisineType;
    /** Geodesic distance in metres from the search origin (bbox centroid or explicit center). */
    private Double distanceMeters;
}
