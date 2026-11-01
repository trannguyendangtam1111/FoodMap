package com.foodmap.trending.domain;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;

/**
 * Materialized output of the trending computation job.
 *
 * Rows are derived and fully rebuildable — no audit timestamps needed.
 * The job writes via INSERT ... ON CONFLICT DO UPDATE (upsert by the unique key).
 *
 * entity_id is intentionally NOT a foreign key — it references either
 * foods.id or restaurants.id depending on entity_type. Integrity is
 * guaranteed by the computation job, not the DB constraint.
 */
@Entity
@Table(
    name = "trending_scores",
    uniqueConstraints = {
        @UniqueConstraint(
            name = "uk_trending_entity_window_region",
            columnNames = {"entity_type", "entity_id", "time_window", "region_key"}
        )
    },
    indexes = {
        @Index(
            name       = "idx_trending_lookup",
            columnList = "entity_type, time_window, region_key, score"
        ),
        @Index(name = "idx_trending_computed_at", columnList = "computed_at")
    }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TrendingScore {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(name = "entity_type", nullable = false, length = 20)
    private EntityType entityType;

    // Logical FK to foods.id or restaurants.id — no DB-level constraint by design
    @Column(name = "entity_id", nullable = false)
    private Long entityId;

    @Column(nullable = false, precision = 12, scale = 4)
    private BigDecimal score;

    @Enumerated(EnumType.STRING)
    @Column(name = "time_window", nullable = false, length = 10)
    private TrendingWindow timeWindow;

    /**
     * Scopes the score to a geographic region.
     * Null = global; "hanoi", "hcmc" = city; geohash prefix = fine-grained.
     */
    @Column(name = "region_key", length = 50)
    private String regionKey;

    // Timestamp of the last computation run that produced this score
    @Column(name = "computed_at", nullable = false)
    private Instant computedAt;

    // ── Equality on unique composite key ──────────────────

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof TrendingScore other)) return false;
        return Objects.equals(entityType, other.entityType)
            && Objects.equals(entityId,   other.entityId)
            && Objects.equals(timeWindow,     other.timeWindow)
            && Objects.equals(regionKey,  other.regionKey);
    }

    @Override
    public int hashCode() {
        return Objects.hash(entityType, entityId, timeWindow, regionKey);
    }
}
