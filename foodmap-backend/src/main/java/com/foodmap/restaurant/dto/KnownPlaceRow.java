package com.foodmap.restaurant.dto;

/**
 * Identity fields of a stored restaurant — what a snapshot ingestion run
 * needs to recognise an already-known place (by Goong place_id, or by its
 * exact normalised name + address) without calling Goong.
 */
public interface KnownPlaceRow {
    Long getId();
    String getGoongPlaceId();
    String getName();
    String getAddress();
    Double getLatitude();
    Double getLongitude();
}
