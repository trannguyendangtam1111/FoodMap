package com.foodmap.food.domain;

import com.foodmap.common.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.BatchSize;

import java.math.BigDecimal;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;

@Entity
@Table(
    name = "foods",
    indexes = {
        @Index(name = "idx_foods_slug",             columnList = "slug",             unique = true),
        @Index(name = "idx_foods_popularity_score", columnList = "popularity_score")
    }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Food extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 150)
    private String name;

    // URL-safe identifier, e.g. "pho-bo-tai" — used in API paths and search
    @Column(nullable = false, unique = true, length = 160)
    private String slug;

    @Column(columnDefinition = "text")
    private String description;

    @Column(length = 100)
    private String cuisine;

    @Column(name = "photo_url", length = 500)
    private String photoUrl;

    // Denormalized score updated by the trending computation job
    @Column(name = "popularity_score", precision = 10, scale = 3)
    @Builder.Default
    private BigDecimal popularityScore = BigDecimal.ZERO;

    // ── Relationships ──────────────────────────────────────

    /**
     * Tags are a stable reference set — MERGE only, never cascade delete.
     * Lazy + BatchSize prevents N+1 when loading a list of foods.
     */
    @ManyToMany(fetch = FetchType.LAZY)
    @JoinTable(
        name = "food_tags_join",
        joinColumns        = @JoinColumn(name = "food_id",  nullable = false),
        inverseJoinColumns = @JoinColumn(name = "tag_id",   nullable = false)
    )
    @BatchSize(size = 20)
    @Builder.Default
    private Set<FoodTag> tags = new HashSet<>();

    // ── Helpers ───────────────────────────────────────────

    public void addTag(FoodTag tag) {
        tags.add(tag);
    }

    public void removeTag(FoodTag tag) {
        tags.remove(tag);
    }

    // ── Equality on natural key ────────────────────────────

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Food other)) return false;
        return slug != null && slug.equals(other.slug);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(slug);
    }
}
