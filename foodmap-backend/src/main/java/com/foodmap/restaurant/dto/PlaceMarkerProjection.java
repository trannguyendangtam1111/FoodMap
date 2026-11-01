package com.foodmap.restaurant.dto;

import java.math.BigDecimal;

/**
 * Spring Data interface projection used by RestaurantRepository#searchPlaces.
 * The native SQL aliases columns in double-quoted camelCase so they bind
 * directly to these getters without naming-strategy gymnastics.
 *
 * Kept package-internal in spirit (only the service maps it to the public
 * PlaceMarkerDTO) — never returned over the wire.
 */
public interface PlaceMarkerProjection {
    Long getId();
    String getName();
    Double getLatitude();
    Double getLongitude();
    BigDecimal getAvgRating();
    Integer getReviewCount();
    Integer getPriceLevel();
    String getCuisineType();
    Double getDistanceMeters();
}
