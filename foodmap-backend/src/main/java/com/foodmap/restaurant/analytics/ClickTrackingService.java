package com.foodmap.restaurant.analytics;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * Records user click events asynchronously so the main request path
 * never blocks on analytics writes.
 *
 * Call recordClick from the restaurant detail endpoint (GET /{id}), or
 * from a dedicated POST /api/v1/restaurants/{id}/click endpoint once
 * the frontend is wired to send explicit click signals.
 *
 * The collected data feeds two future systems:
 *   1. Trending computation (ScheduledTrendingJob): clicks per restaurant
 *      in a rolling window → supplement / replace view_count signal.
 *   2. Personalisation (RankingService): top cuisines per user_id →
 *      PersonalizationContext.preferredCategories → categoryScore boost.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ClickTrackingService {

    private final ClickEventRepository clickEventRepository;

    @Async
    @Transactional
    public void recordClick(Long restaurantId, Long userId,
                             String sessionId, String searchQuery) {
        try {
            clickEventRepository.save(ClickEvent.builder()
                .restaurantId(restaurantId)
                .userId(userId)
                .sessionId(sessionId)
                .searchQuery(searchQuery)
                .clickedAt(Instant.now())
                .build());
        } catch (Exception e) {
            // Never let analytics failures surface to the caller.
            log.warn("Failed to record click for restaurant={}: {}", restaurantId, e.getMessage());
        }
    }
}
