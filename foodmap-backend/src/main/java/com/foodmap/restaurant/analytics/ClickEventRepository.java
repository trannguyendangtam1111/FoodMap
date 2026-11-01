package com.foodmap.restaurant.analytics;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;

@Repository
public interface ClickEventRepository extends JpaRepository<ClickEvent, Long> {

    /**
     * Count clicks on a restaurant within a time window.
     * Future trending job hook: replace TrendingScore.score for RESTAURANT type.
     */
    @Query("SELECT COUNT(c) FROM ClickEvent c WHERE c.restaurantId = :restaurantId AND c.clickedAt >= :since")
    long countByRestaurantIdSince(@Param("restaurantId") Long restaurantId, @Param("since") Instant since);

    /**
     * Find the most-clicked cuisine types for a user, ordered by click frequency.
     * Future personalisation hook: populate PersonalizationContext.preferredCategories.
     */
    @Query(value = """
        SELECT r.cuisine_type
        FROM click_events c
        JOIN restaurants r ON r.id = c.restaurant_id
        WHERE c.user_id = :userId
          AND c.clicked_at >= :since
          AND r.cuisine_type IS NOT NULL
        GROUP BY r.cuisine_type
        ORDER BY COUNT(*) DESC
        LIMIT :topN
        """, nativeQuery = true)
    List<String> findTopCuisinesByUser(@Param("userId") Long userId,
                                        @Param("since") Instant since,
                                        @Param("topN") int topN);
}
