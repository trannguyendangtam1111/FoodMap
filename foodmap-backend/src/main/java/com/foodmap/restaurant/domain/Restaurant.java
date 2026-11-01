package com.foodmap.restaurant.domain;

import com.foodmap.common.converter.StringListConverter;
import com.foodmap.common.converter.StringMapConverter;
import com.foodmap.common.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.BatchSize;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Entity
@Table(
    name = "restaurants",
    indexes = {
        @Index(name = "idx_restaurants_goong_place_id", columnList = "goong_place_id", unique = true),
        @Index(name = "idx_restaurants_lat_lng",        columnList = "latitude, longitude"),
        @Index(name = "idx_restaurants_avg_rating",     columnList = "avg_rating"),
        @Index(name = "idx_restaurants_cuisine_type",   columnList = "cuisine_type"),
        @Index(name = "idx_restaurants_last_synced_at", columnList = "last_synced_at"),
        @Index(name = "idx_restaurants_is_active",      columnList = "is_active")
    }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Restaurant extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // ── Goong bridge key ──────────────────────────────────
    // Unique identifier from Goong Places API. Used for idempotent upserts.
    @Column(name = "goong_place_id", nullable = false, unique = true, length = 512)
    private String goongPlaceId;

    // ── Cached provider data (from Goong) ─────────────────

    @Column(nullable = false, length = 255)
    private String name;

    @Column(length = 500)
    private String address;

    @Column(nullable = false)
    private Double latitude;

    @Column(nullable = false)
    private Double longitude;

    @Column(length = 50)
    private String phone;

    @Column(columnDefinition = "text")
    private String website;

    // Stored as JSON: { "monday": "06:00-21:00", "tuesday": "06:00-21:00", ... }
    @Convert(converter = StringMapConverter.class)
    @Column(name = "opening_hours", columnDefinition = "text")
    private Map<String, String> openingHours;

    // Array of photo URLs from Goong, stored as JSON
    @Convert(converter = StringListConverter.class)
    @Column(columnDefinition = "text")
    @Builder.Default
    private List<String> photos = new ArrayList<>();

    // Full raw Goong API payload — preserved for forward-compatibility
    @Column(name = "provider_raw", columnDefinition = "text")
    private String providerRaw;

    // ── FoodMap business data ─────────────────────────────

    @Column(name = "cuisine_type", length = 100)
    private String cuisineType;

    // 1 = budget, 2 = moderate, 3 = upscale, 4 = fine dining
    @Column(name = "price_level")
    private Integer priceLevel;

    // Denormalized — recomputed on every review write to avoid JOIN on read-heavy endpoints
    @Column(name = "avg_rating", precision = 3, scale = 2)
    @Builder.Default
    private BigDecimal avgRating = BigDecimal.ZERO;

    @Column(name = "review_count", nullable = false)
    @Builder.Default
    private Integer reviewCount = 0;

    // Rolled up periodically from restaurant_views
    @Column(name = "view_count", nullable = false)
    @Builder.Default
    private Long viewCount = 0L;

    @Column(name = "is_active", nullable = false)
    @Builder.Default
    private boolean isActive = true;

    // Null means never synced; used by sync scheduler to detect stale cache
    @Column(name = "last_synced_at")
    private Instant lastSyncedAt;

    // Secondary dedup key — sha1(unaccent(lower(name)) + rounded lat/lng).
    // Used to merge near-duplicate places when Goong reissues a place_id or
    // when (eventually) a second provider produces the same physical place.
    // Computed in RestaurantSyncService.upsert.
    @Column(name = "dedup_fingerprint", length = 64)
    private String dedupFingerprint;

    // ── Relationships ──────────────────────────────────────

    /**
     * Dishes served at this restaurant.
     * Cascade ALL + orphanRemoval: if a RestaurantFood is removed from this list
     * the row in restaurant_foods is deleted. Adding a food requires going through
     * this collection, preserving the invariant.
     */
    @OneToMany(
        mappedBy    = "restaurant",
        cascade     = CascadeType.ALL,
        orphanRemoval = true,
        fetch       = FetchType.LAZY
    )
    @BatchSize(size = 30)
    @Builder.Default
    private List<RestaurantFood> restaurantFoods = new ArrayList<>();

    // ── Helpers ───────────────────────────────────────────

    public void addFood(RestaurantFood rf) {
        rf.setRestaurant(this);
        restaurantFoods.add(rf);
    }

    public void removeFood(RestaurantFood rf) {
        restaurantFoods.remove(rf);
        rf.setRestaurant(null);
    }

    public void incrementReviewCount() {
        this.reviewCount++;
    }

    public void decrementReviewCount() {
        if (this.reviewCount > 0) this.reviewCount--;
    }

    // ── Equality on natural key ────────────────────────────

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Restaurant other)) return false;
        return goongPlaceId != null && goongPlaceId.equals(other.goongPlaceId);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(goongPlaceId);
    }
}
