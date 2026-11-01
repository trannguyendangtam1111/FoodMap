package com.foodmap.review.domain;

import com.foodmap.common.converter.StringListConverter;
import com.foodmap.common.entity.BaseEntity;
import com.foodmap.food.domain.Food;
import com.foodmap.restaurant.domain.Restaurant;
import com.foodmap.user.domain.User;
import jakarta.persistence.*;
import lombok.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * DUPLICATE-REVIEW UNIQUENESS — enforced by two partial unique indexes in the DB
 * (V3__review_integrity_constraints.sql). JPA @UniqueConstraint cannot express
 * partial indexes, so they live in the migration only.
 *
 *   uk_reviews_one_per_user_restaurant_general
 *     → UNIQUE (user_id, restaurant_id) WHERE food_id IS NULL
 *     → one general review per (user, restaurant)
 *
 *   uk_reviews_one_per_user_restaurant_food
 *     → UNIQUE (user_id, restaurant_id, food_id) WHERE food_id IS NOT NULL
 *     → one dish review per (user, restaurant, food)
 *
 * A plain UNIQUE(user_id, restaurant_id, food_id) would not work because
 * PostgreSQL treats NULL != NULL in unique constraints — general reviews
 * (food_id IS NULL) would never be considered duplicates under a regular constraint.
 */
@Entity
@Table(
    name = "reviews",
    indexes = {
        @Index(name = "idx_reviews_restaurant_id",         columnList = "restaurant_id"),
        @Index(name = "idx_reviews_user_id",               columnList = "user_id"),
        @Index(name = "idx_reviews_food_id",               columnList = "food_id"),
        @Index(name = "idx_reviews_restaurant_created_at", columnList = "restaurant_id, created_at")
    }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Review extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // ── Relationships ──────────────────────────────────────

    /**
     * LAZY on all FK sides — never load user/restaurant/food implicitly.
     * Service layer decides what to JOIN or fetch separately.
     */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "restaurant_id", nullable = false)
    private Restaurant restaurant;

    /**
     * A review can target the restaurant in general (null) or a specific dish.
     * ON DELETE SET NULL: if a food entry is removed, the review text is preserved.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "food_id", foreignKey = @ForeignKey(name = "fk_review_food"))
    private Food food;

    // ── Review content ────────────────────────────────────

    @Column(nullable = false)
    private Integer rating;

    @Column(columnDefinition = "text")
    private String comment;

    // Array of user-uploaded photo URLs
    @Convert(converter = StringListConverter.class)
    @Column(columnDefinition = "text")
    @Builder.Default
    private List<String> photos = new ArrayList<>();

    // ── Equality on surrogate PK ───────────────────────────
    // No natural business key here; two identical review texts by the same user
    // at different times are distinct rows.

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Review other)) return false;
        return id != null && id.equals(other.id);
    }

    @Override
    public int hashCode() {
        return getClass().hashCode();
    }
}
