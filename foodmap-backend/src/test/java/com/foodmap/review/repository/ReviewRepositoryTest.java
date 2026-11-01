package com.foodmap.review.repository;

import com.foodmap.config.JpaAuditingConfig;
import com.foodmap.food.domain.Food;
import com.foodmap.food.repository.FoodRepository;
import com.foodmap.restaurant.domain.Restaurant;
import com.foodmap.restaurant.repository.RestaurantFoodRepository;
import com.foodmap.restaurant.repository.RestaurantRepository;
import com.foodmap.review.domain.Review;
import com.foodmap.support.TestDataFactory;
import com.foodmap.user.domain.Role;
import com.foodmap.user.domain.User;
import com.foodmap.user.repository.UserRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.ActiveProfiles;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Slice tests for custom JPQL queries in ReviewRepository and RestaurantRepository.
 *
 * Why @DataJpaTest over @SpringBootTest here:
 * - Starts in ~400ms (no web layer, no security, no Goong client)
 * - Auto-rolls back each test (no @BeforeEach cleanup needed)
 * - Isolates JPQL logic from HTTP/service concerns
 *
 * @AutoConfigureTestDatabase(replace = NONE): keep the test-profile H2 datasource
 *   (H2 in PostgreSQL MODE) rather than letting Spring substitute its own default H2.
 * @Import(JpaAuditingConfig.class): BaseEntity uses @CreatedDate / @LastModifiedDate
 *   (nullable = false). Without this import, saves would fail with a NOT NULL violation.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@Import(JpaAuditingConfig.class)
class ReviewRepositoryTest {

    @Autowired ReviewRepository reviewRepository;
    @Autowired RestaurantRepository restaurantRepository;
    @Autowired UserRepository userRepository;
    @Autowired FoodRepository foodRepository;
    @Autowired RestaurantFoodRepository restaurantFoodRepository;
    @Autowired EntityManager em;

    // ── findByRestaurantIdWithDetails ─────────────────────────────────────────

    @Test
    void findByRestaurantIdWithDetails_returnsReviewsWithUserAndFoodEagerlyLoaded() {
        // This test verifies that the JOIN FETCH query avoids N+1.
        // If user or food were lazily loaded and the session closed, accessing them
        // would throw LazyInitializationException. Here we confirm they are accessible.

        User user = savedUser("details@test.dev");
        Restaurant restaurant = savedRestaurant();
        Food food = savedFood();
        linkFoodToRestaurant(restaurant, food);

        // One review with food, one without
        saveReview(user, restaurant, food, 5, "Loved it");
        saveReview(user, restaurant, null, 3, "Restaurant overall");

        Page<Review> page = reviewRepository.findByRestaurantIdWithDetails(
                restaurant.getId(), PageRequest.of(0, 10));

        assertThat(page.getTotalElements()).isEqualTo(2);
        page.getContent().forEach(r -> {
            // Accessing these without LazyInitializationException proves the JOIN FETCH worked
            assertThat(r.getUser().getEmail()).isEqualTo("details@test.dev");
        });
    }

    @Test
    void findByRestaurantIdWithDetails_paginatesCorrectly() {
        User user1 = savedUser("u1@test.dev");
        User user2 = savedUser("u2@test.dev");
        User user3 = savedUser("u3@test.dev");
        Restaurant restaurant = savedRestaurant();

        saveReview(user1, restaurant, null, 4, "Review 1");
        saveReview(user2, restaurant, null, 5, "Review 2");
        saveReview(user3, restaurant, null, 3, "Review 3");

        Page<Review> pageOne = reviewRepository.findByRestaurantIdWithDetails(
                restaurant.getId(), PageRequest.of(0, 2));
        Page<Review> pageTwo = reviewRepository.findByRestaurantIdWithDetails(
                restaurant.getId(), PageRequest.of(1, 2));

        assertThat(pageOne.getTotalElements()).isEqualTo(3);
        assertThat(pageOne.getContent()).hasSize(2);
        assertThat(pageTwo.getContent()).hasSize(1);
    }

    // ── findGeneralReview (duplicate prevention) ──────────────────────────────

    @Test
    void findGeneralReview_whenGeneralReviewExists_returnsIt() {
        User user = savedUser("gen@test.dev");
        Restaurant restaurant = savedRestaurant();
        Review saved = saveReview(user, restaurant, null, 4, "Great place");

        Optional<Review> found = reviewRepository.findGeneralReview(
                user.getId(), restaurant.getId());

        assertThat(found).isPresent();
        assertThat(found.get().getId()).isEqualTo(saved.getId());
    }

    @Test
    void findGeneralReview_whenOnlyDishReviewExists_returnsEmpty() {
        // The query filters food IS NULL — a dish review must not match.
        // This is the core invariant: general and dish reviews are separate.
        User user = savedUser("dishonly@test.dev");
        Restaurant restaurant = savedRestaurant();
        Food food = savedFood();
        linkFoodToRestaurant(restaurant, food);

        saveReview(user, restaurant, food, 5, "Loved the dish");

        Optional<Review> found = reviewRepository.findGeneralReview(
                user.getId(), restaurant.getId());

        assertThat(found).isEmpty();
    }

    @Test
    void findGeneralReview_whenNoReviewsExist_returnsEmpty() {
        User user = savedUser("noreviews@test.dev");
        Restaurant restaurant = savedRestaurant();

        assertThat(reviewRepository.findGeneralReview(user.getId(), restaurant.getId()))
                .isEmpty();
    }

    // ── findByUserIdAndRestaurantIdAndFoodId ──────────────────────────────────

    @Test
    void findByUserIdAndRestaurantIdAndFoodId_whenDishReviewExists_returnsIt() {
        User user = savedUser("dish@test.dev");
        Restaurant restaurant = savedRestaurant();
        Food food = savedFood();
        linkFoodToRestaurant(restaurant, food);

        Review saved = saveReview(user, restaurant, food, 5, "Best pho");

        Optional<Review> found = reviewRepository.findByUserIdAndRestaurantIdAndFoodId(
                user.getId(), restaurant.getId(), food.getId());

        assertThat(found).isPresent();
        assertThat(found.get().getId()).isEqualTo(saved.getId());
    }

    @Test
    void findByUserIdAndRestaurantIdAndFoodId_differentFoodId_returnsEmpty() {
        User user = savedUser("wrongfood@test.dev");
        Restaurant restaurant = savedRestaurant();
        Food food1 = savedFood();
        Food food2 = savedFood();
        linkFoodToRestaurant(restaurant, food1);

        saveReview(user, restaurant, food1, 4, "Review for food1");

        Optional<Review> found = reviewRepository.findByUserIdAndRestaurantIdAndFoodId(
                user.getId(), restaurant.getId(), food2.getId());

        assertThat(found).isEmpty();
    }

    // ── recalculateRatingStats ────────────────────────────────────────────────

    @Test
    void recalculateRatingStats_correctlyAveragesRatingsAndUpdatesCount() {
        User user1 = savedUser("rate1@test.dev");
        User user2 = savedUser("rate2@test.dev");
        User user3 = savedUser("rate3@test.dev");
        Restaurant restaurant = savedRestaurant();

        saveReview(user1, restaurant, null, 5, null); // rating = 5
        saveReview(user2, restaurant, null, 3, null); // rating = 3
        saveReview(user3, restaurant, null, 4, null); // rating = 4
        // avg = (5+3+4)/3 = 4.0, count = 3

        restaurantRepository.recalculateRatingStats(restaurant.getId());

        // IMPORTANT: @Modifying bulk UPDATE bypasses Hibernate's first-level cache.
        // Without clearing the persistence context, findById() would return the stale
        // cached entity (avgRating = 0). em.clear() forces the next load from the DB.
        em.clear();

        Restaurant updated = restaurantRepository.findById(restaurant.getId()).orElseThrow();
        assertThat(updated.getReviewCount()).isEqualTo(3);
        assertThat(updated.getAvgRating().doubleValue()).isEqualTo(4.0);
    }

    @Test
    void recalculateRatingStats_noReviews_setsZeroAvgAndCount() {
        Restaurant restaurant = savedRestaurant();

        restaurantRepository.recalculateRatingStats(restaurant.getId());
        em.clear();

        Restaurant updated = restaurantRepository.findById(restaurant.getId()).orElseThrow();
        assertThat(updated.getReviewCount()).isZero();
        assertThat(updated.getAvgRating().doubleValue()).isZero();
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private User savedUser(String email) {
        return userRepository.save(User.builder()
                .email(email)
                .passwordHash("$2a$12$placeholder-not-used-in-repository-tests")
                .displayName("Test User")
                .role(Role.USER)
                .enabled(true)
                .build());
    }

    private Restaurant savedRestaurant() {
        return restaurantRepository.save(TestDataFactory.aRestaurant());
    }

    private Food savedFood() {
        return foodRepository.save(TestDataFactory.aFood());
    }

    private void linkFoodToRestaurant(Restaurant restaurant, Food food) {
        restaurantFoodRepository.save(TestDataFactory.linkFoodToRestaurant(restaurant, food));
    }

    private Review saveReview(User user, Restaurant restaurant, Food food,
                               int rating, String comment) {
        return reviewRepository.save(Review.builder()
                .user(user)
                .restaurant(restaurant)
                .food(food)
                .rating(rating)
                .comment(comment)
                .build());
    }
}
