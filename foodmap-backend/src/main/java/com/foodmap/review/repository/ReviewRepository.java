package com.foodmap.review.repository;

import com.foodmap.review.domain.Review;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Optional;

@Repository
public interface ReviewRepository extends JpaRepository<Review, Long> {

    Page<Review> findByRestaurantId(Long restaurantId, Pageable pageable);

    /**
     * Loads reviews with user and food in a single JOIN FETCH, avoiding N+1.
     * countQuery is separate so Hibernate doesn't try to count a JOIN FETCH result.
     */
    @Query(
        value = """
            SELECT r FROM Review r
            JOIN FETCH r.user
            LEFT JOIN FETCH r.food
            WHERE r.restaurant.id = :restaurantId
            ORDER BY r.createdAt DESC
            """,
        countQuery = "SELECT COUNT(r) FROM Review r WHERE r.restaurant.id = :restaurantId"
    )
    Page<Review> findByRestaurantIdWithDetails(
        @Param("restaurantId") Long restaurantId, Pageable pageable
    );

    Page<Review> findByUserId(Long userId, Pageable pageable);

    Page<Review> findByRestaurantIdAndFoodId(Long restaurantId, Long foodId, Pageable pageable);

    // Prevent duplicate: one review per (user, restaurant) when food is null
    @Query("""
        SELECT r FROM Review r
        WHERE r.user.id = :userId
          AND r.restaurant.id = :restaurantId
          AND r.food IS NULL
        """)
    Optional<Review> findGeneralReview(@Param("userId") Long userId, @Param("restaurantId") Long restaurantId);

    // Prevent duplicate: one review per (user, restaurant, food)
    Optional<Review> findByUserIdAndRestaurantIdAndFoodId(Long userId, Long restaurantId, Long foodId);

    // Used by trending computation — reviews in a time window for a given food
    @Query("""
        SELECT r FROM Review r
        JOIN r.restaurant res
        JOIN res.restaurantFoods rf
        WHERE rf.food.id = :foodId
          AND r.createdAt >= :since
        """)
    Page<Review> findReviewsForFoodSince(
        @Param("foodId") Long foodId,
        @Param("since") Instant since,
        Pageable pageable
    );

    long countByRestaurantId(Long restaurantId);
}
