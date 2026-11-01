package com.foodmap.restaurant.dto;

/**
 * Native-query projection: one restaurant inside an availability radius, with
 * its PostGIS (ellipsoidal) distance from the scope centre. Server-internal.
 */
public interface AvailabilityCandidateRow {
    Long getId();
    String getName();
    String getCuisineType();
    Double getLatitude();
    Double getLongitude();
    Double getDistanceMeters();
}
