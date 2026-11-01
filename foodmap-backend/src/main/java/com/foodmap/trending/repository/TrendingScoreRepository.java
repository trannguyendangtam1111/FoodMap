package com.foodmap.trending.repository;

import com.foodmap.trending.domain.EntityType;
import com.foodmap.trending.domain.TrendingScore;
import com.foodmap.trending.domain.TrendingWindow;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface TrendingScoreRepository extends JpaRepository<TrendingScore, Long> {

    // Primary read path: top N trending entities for a type/window/region
    List<TrendingScore> findByEntityTypeAndTimeWindowAndRegionKeyOrderByScoreDesc(
        EntityType entityType,
        TrendingWindow window,
        String regionKey
    );

    // Upsert target lookup
    Optional<TrendingScore> findByEntityTypeAndEntityIdAndTimeWindowAndRegionKey(
        EntityType entityType,
        Long entityId,
        TrendingWindow window,
        String regionKey
    );

    // Wipe stale scores before a recompute run for a given window/region
    @Modifying
    @Query("""
        DELETE FROM TrendingScore t
        WHERE t.entityType = :entityType
          AND t.timeWindow = :timeWindow
          AND (:regionKey IS NULL AND t.regionKey IS NULL
               OR t.regionKey = :regionKey)
        """)
    void deleteByEntityTypeAndTimeWindowAndRegionKey(
        @Param("entityType") EntityType entityType,
        @Param("timeWindow") TrendingWindow timeWindow,
        @Param("regionKey") String regionKey
    );
}
