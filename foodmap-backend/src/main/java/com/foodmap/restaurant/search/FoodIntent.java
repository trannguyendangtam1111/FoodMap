package com.foodmap.restaurant.search;

import java.util.List;

/**
 * The catalog food a search query names ("phở", "pho", "cà phê"), as seen by
 * {@link SearchRanker}. Built by the search service from the food catalog, so
 * the ranker itself stays free of I/O and catalog details.
 */
public interface FoodIntent {

    /** The resolved food key. */
    String foodKey();

    /** Catalog evidence for the food: a keyword or synonym in the name, or the food on the menu. */
    boolean hasEvidence(SearchRanker.Candidate candidate);

    /** The matched name tokens are a different accented word than this food ("phố" vs "phở"). */
    boolean isAccentVariant(List<String> matchedNameTokens);
}
