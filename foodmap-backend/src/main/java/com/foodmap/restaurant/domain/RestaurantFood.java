package com.foodmap.restaurant.domain;

import com.foodmap.food.domain.Food;
import jakarta.persistence.*;
import lombok.*;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;

/**
 * Join entity between Restaurant and Food.
 * Uses a surrogate PK for simpler querying; the business uniqueness
 * is enforced by the UNIQUE constraint on (restaurant_id, food_id).
 *
 * Not extending BaseEntity intentionally — this join row has a creation time
 * but updates are rare and updatedAt is not meaningful here.
 */
@Entity
@Table(
    name = "restaurant_foods",
    uniqueConstraints = {
        @UniqueConstraint(name = "uk_restaurant_food", columnNames = {"restaurant_id", "food_id"})
    },
    indexes = {
        @Index(name = "idx_rf_food_id",             columnList = "food_id"),
        @Index(name = "idx_rf_restaurant_signature", columnList = "restaurant_id, is_signature")
    }
)
@EntityListeners(AuditingEntityListener.class)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RestaurantFood {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * Owning side of the relationship.
     * LAZY — food detail is fetched separately when the restaurant detail page loads.
     */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "restaurant_id", nullable = false)
    private Restaurant restaurant;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "food_id", nullable = false)
    private Food food;

    // Optional price at this specific restaurant (menus vary between locations)
    @Column(precision = 12, scale = 2)
    private BigDecimal price;

    // Signature dishes are surfaced prominently on the restaurant detail page
    @Column(name = "is_signature", nullable = false)
    @Builder.Default
    private boolean isSignature = false;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    // ── Equality on composite business key ────────────────

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof RestaurantFood other)) return false;
        return Objects.equals(restaurant, other.restaurant)
            && Objects.equals(food, other.food);
    }

    @Override
    public int hashCode() {
        return Objects.hash(restaurant, food);
    }
}
