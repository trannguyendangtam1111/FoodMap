package com.foodmap.restaurant.dto;

import java.math.BigDecimal;

/**
 * Native-query projection: one stage-1 search candidate, with the pg_trgm
 * similarity of its folded name to the folded query. Never returned over the wire.
 */
public interface SearchCandidateRow {
    Long getId();
    String getName();
    String getAddress();
    BigDecimal getAvgRating();
    Integer getReviewCount();
    Double getLatitude();
    Double getLongitude();
    Double getNameSimilarity();
}
