package com.foodmap.restaurant.ranking;

import java.util.List;

/**
 * Carries user-specific signals into the scoring pipeline.
 *
 * Today only categoryQuery is active. The other fields are stubs for
 * future personalization:
 *   - userId          → look up click history / favourite cuisines per user
 *   - preferredCategories → boost cuisines this user historically clicks
 *   - trendingBoost   → apply trending-score weight from TrendingScoreRepository
 *
 * Pass anonymous() when no user session is available.
 */
public record PersonalizationContext(
    Long userId,
    List<String> preferredCategories,
    String categoryQuery
) {

    public static PersonalizationContext anonymous(String categoryQuery) {
        return new PersonalizationContext(null, List.of(), categoryQuery);
    }

    public static PersonalizationContext anonymous() {
        return anonymous(null);
    }

    public static PersonalizationContext forUser(Long userId, List<String> preferredCategories,
                                                  String categoryQuery) {
        return new PersonalizationContext(userId, List.copyOf(preferredCategories), categoryQuery);
    }
}
