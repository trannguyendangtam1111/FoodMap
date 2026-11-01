package com.foodmap.restaurant.analytics;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

/**
 * Records a user click on a restaurant card in search or discovery results.
 *
 * No FK to restaurants/users — analytics tables are insert-heavy and are
 * read by async jobs, not the live request path. Loose coupling avoids
 * lock contention and lets click history survive restaurant soft-deletes.
 *
 * Future use:
 *   - Trending signals: COUNT clicks per restaurant in a time window.
 *   - Personalisation: GROUP BY user_id, cuisine_type → preferred categories.
 *   - CTR scoring: clicks / impressions per search_query + restaurant pair.
 */
@Entity
@Table(
    name = "click_events",
    indexes = {
        @Index(name = "idx_click_events_restaurant_clicked_at", columnList = "restaurant_id, clicked_at DESC"),
        @Index(name = "idx_click_events_user_id",               columnList = "user_id")
    }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ClickEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Logical reference to restaurants.id (no FK by design). */
    @Column(name = "restaurant_id", nullable = false)
    private Long restaurantId;

    /** Null for anonymous sessions. */
    @Column(name = "user_id")
    private Long userId;

    /** Client-generated session token for anonymous click clustering. */
    @Column(name = "session_id", length = 128)
    private String sessionId;

    /** The query string or category filter active when the user clicked. */
    @Column(name = "search_query", length = 500)
    private String searchQuery;

    @Column(name = "clicked_at", nullable = false)
    private Instant clickedAt;
}
