package com.foodmap.review;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.foodmap.auth.repository.RefreshTokenRepository;
import com.foodmap.food.domain.Food;
import com.foodmap.food.repository.FoodRepository;
import com.foodmap.restaurant.domain.Restaurant;
import com.foodmap.restaurant.domain.RestaurantFood;
import com.foodmap.restaurant.repository.RestaurantFoodRepository;
import com.foodmap.restaurant.repository.RestaurantRepository;
import com.foodmap.review.repository.ReviewRepository;
import com.foodmap.security.JwtTokenProvider;
import com.foodmap.support.TestDataFactory;
import com.foodmap.user.domain.Role;
import com.foodmap.user.domain.User;
import com.foodmap.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration tests for the review business logic.
 *
 * Covers the three hardest invariants in the system:
 *
 *   1. Duplicate prevention — one general review per (user, restaurant),
 *      one dish review per (user, restaurant, food). The application-level
 *      check must catch duplicates and return a descriptive 400, not a
 *      generic DB 500. (The partial unique index is the DB-level safety
 *      net — it requires PostgreSQL and would be tested with Testcontainers.)
 *
 *   2. Menu integrity — you can only review a food at a restaurant that
 *      actually serves it. Without this guard, users could submit reviews
 *      for semantically invalid (food, restaurant) combinations.
 *
 *   3. Denormalized stat recalculation — after each review write,
 *      Restaurant.avgRating and Restaurant.reviewCount must be updated
 *      correctly. This is the most likely place for a race condition to
 *      surface as a data correctness bug.
 *
 *   4. Delete authorization — only the review author or an ADMIN may delete.
 *      A regular user deleting another user's review must return 401.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ReviewIntegrationTest {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired UserRepository userRepository;
    @Autowired RefreshTokenRepository refreshTokenRepository;
    @Autowired ReviewRepository reviewRepository;
    @Autowired RestaurantRepository restaurantRepository;
    @Autowired RestaurantFoodRepository restaurantFoodRepository;
    @Autowired FoodRepository foodRepository;
    @Autowired PasswordEncoder passwordEncoder;
    @Autowired JwtTokenProvider jwtTokenProvider;

    private static final String PASSWORD = "SecurePass1!";

    // Shared test state — re-initialized per test in setUp()
    private Restaurant restaurant;
    private Food food;
    private String userToken;
    private User user;

    @BeforeEach
    void setUp() {
        // Clean in FK-safe order
        refreshTokenRepository.deleteAll();
        reviewRepository.deleteAll();
        restaurantRepository.deleteAll(); // cascades to restaurant_foods
        userRepository.deleteAll();
        foodRepository.deleteAll();

        // Create a user and generate a real JWT
        user = userRepository.save(
                TestDataFactory.aUser("reviewer@test.dev", passwordEncoder.encode(PASSWORD)));
        userToken = jwtTokenProvider.generateAccessToken(user);

        // Create a restaurant with one food on the menu
        food = foodRepository.save(TestDataFactory.aFood());
        restaurant = restaurantRepository.save(TestDataFactory.aRestaurant());

        RestaurantFood link = TestDataFactory.linkFoodToRestaurant(restaurant, food);
        restaurantFoodRepository.save(link);
    }

    // ── List reviews (public) ─────────────────────────────────────────────────

    @Test
    void listReviews_publicEndpoint_noAuthRequired() throws Exception {
        mockMvc.perform(get("/api/v1/restaurants/{id}/reviews", restaurant.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.content").isArray());
    }

    @Test
    void listReviews_unknownRestaurant_returns404() throws Exception {
        mockMvc.perform(get("/api/v1/restaurants/99999/reviews"))
                .andExpect(status().isNotFound());
    }

    // ── Create review — auth guard ────────────────────────────────────────────

    @Test
    void createReview_withoutAuth_returns401() throws Exception {
        mockMvc.perform(post("/api/v1/restaurants/{id}/reviews", restaurant.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"rating":4}
                                """))
                .andExpect(status().isUnauthorized());
    }

    // ── Create review — validation ────────────────────────────────────────────

    @Test
    void createReview_missingRating_returns400() throws Exception {
        // Validation errors return ErrorResponse directly (not wrapped in ApiResponse).
        // Shape: { status, error, message, path, fieldErrors: { rating: "..." } }
        mockMvc.perform(post("/api/v1/restaurants/{id}/reviews", restaurant.getId())
                        .header("Authorization", "Bearer " + userToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.rating").exists());
    }

    @Test
    void createReview_ratingBelowMinimum_returns400() throws Exception {
        mockMvc.perform(post("/api/v1/restaurants/{id}/reviews", restaurant.getId())
                        .header("Authorization", "Bearer " + userToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"rating":0}
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    void createReview_ratingAboveMaximum_returns400() throws Exception {
        mockMvc.perform(post("/api/v1/restaurants/{id}/reviews", restaurant.getId())
                        .header("Authorization", "Bearer " + userToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"rating":6}
                                """))
                .andExpect(status().isBadRequest());
    }

    // ── Create review — happy path ────────────────────────────────────────────

    @Test
    void createReview_generalReview_returns201WithReviewData() throws Exception {
        mockMvc.perform(post("/api/v1/restaurants/{id}/reviews", restaurant.getId())
                        .header("Authorization", "Bearer " + userToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"rating":4,"comment":"Great atmosphere!"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.rating").value(4))
                .andExpect(jsonPath("$.data.comment").value("Great atmosphere!"))
                .andExpect(jsonPath("$.data.food").doesNotExist())   // general review has no food
                .andExpect(jsonPath("$.data.user.id").value(user.getId()));
    }

    @Test
    void createReview_withFoodOnMenu_returns201WithFoodData() throws Exception {
        mockMvc.perform(post("/api/v1/restaurants/{id}/reviews", restaurant.getId())
                        .header("Authorization", "Bearer " + userToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"rating":5,"comment":"Best dish!","foodId":%d}
                                """.formatted(food.getId())))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.food.id").value(food.getId()))
                .andExpect(jsonPath("$.data.rating").value(5));
    }

    // ── Create review — duplicate prevention ──────────────────────────────────

    @Test
    void createReview_duplicateGeneralReview_returns400() throws Exception {
        // First general review
        mockMvc.perform(post("/api/v1/restaurants/{id}/reviews", restaurant.getId())
                        .header("Authorization", "Bearer " + userToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"rating":4}
                                """))
                .andExpect(status().isCreated());

        // Second general review from the same user for the same restaurant
        mockMvc.perform(post("/api/v1/restaurants/{id}/reviews", restaurant.getId())
                        .header("Authorization", "Bearer " + userToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"rating":2,"comment":"Changed my mind"}
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    void createReview_duplicateDishReview_returns400() throws Exception {
        String dishBody = """
                {"rating":5,"foodId":%d}
                """.formatted(food.getId());

        // First dish review — OK
        mockMvc.perform(post("/api/v1/restaurants/{id}/reviews", restaurant.getId())
                        .header("Authorization", "Bearer " + userToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(dishBody))
                .andExpect(status().isCreated());

        // Second dish review for the same (user, restaurant, food) triple
        mockMvc.perform(post("/api/v1/restaurants/{id}/reviews", restaurant.getId())
                        .header("Authorization", "Bearer " + userToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(dishBody))
                .andExpect(status().isBadRequest());
    }

    @Test
    void createReview_generalAndDishReviewCoexist_bothAllowed() throws Exception {
        // A user may leave one general review AND one dish review for the same restaurant
        // They are separate uniqueness constraints.
        mockMvc.perform(post("/api/v1/restaurants/{id}/reviews", restaurant.getId())
                        .header("Authorization", "Bearer " + userToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"rating":4}
                                """))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/v1/restaurants/{id}/reviews", restaurant.getId())
                        .header("Authorization", "Bearer " + userToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"rating":5,"foodId":%d}
                                """.formatted(food.getId())))
                .andExpect(status().isCreated());
    }

    // ── Create review — menu integrity ────────────────────────────────────────

    @Test
    void createReview_foodNotOnRestaurantMenu_returns400() throws Exception {
        // Create a food that is NOT linked to this restaurant
        Food otherFood = foodRepository.save(TestDataFactory.aFood());

        mockMvc.perform(post("/api/v1/restaurants/{id}/reviews", restaurant.getId())
                        .header("Authorization", "Bearer " + userToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"rating":3,"foodId":%d}
                                """.formatted(otherFood.getId())))
                .andExpect(status().isBadRequest());
    }

    @Test
    void createReview_nonExistentFood_returns404() throws Exception {
        mockMvc.perform(post("/api/v1/restaurants/{id}/reviews", restaurant.getId())
                        .header("Authorization", "Bearer " + userToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"rating":3,"foodId":99999}
                                """))
                .andExpect(status().isNotFound());
    }

    // ── Denormalized rating stats ─────────────────────────────────────────────

    @Test
    void createReview_updatesRestaurantRatingAndCount() throws Exception {
        // Post two reviews with known ratings
        postReview(userToken, restaurant.getId(), 4, null);

        User user2 = userRepository.save(
                TestDataFactory.aUser("user2@test.dev", passwordEncoder.encode(PASSWORD)));
        String token2 = jwtTokenProvider.generateAccessToken(user2);
        postReview(token2, restaurant.getId(), 2, null);

        // Both reviews committed — re-fetch the restaurant from DB
        Restaurant updated = restaurantRepository.findById(restaurant.getId()).orElseThrow();

        assertThat(updated.getReviewCount()).isEqualTo(2);
        // avgRating should be (4 + 2) / 2 = 3.0
        assertThat(updated.getAvgRating().doubleValue()).isEqualTo(3.0);
    }

    @Test
    void deleteReview_updatesRestaurantStats() throws Exception {
        postReview(userToken, restaurant.getId(), 4, null);

        User user2 = userRepository.save(
                TestDataFactory.aUser("user2b@test.dev", passwordEncoder.encode(PASSWORD)));
        String token2 = jwtTokenProvider.generateAccessToken(user2);
        Long reviewId2 = postReview(token2, restaurant.getId(), 2, null);

        // Delete user2's review — avgRating must recalculate to just user's 4-star review
        mockMvc.perform(delete("/api/v1/restaurants/{rId}/reviews/{rvId}",
                        restaurant.getId(), reviewId2)
                        .header("Authorization", "Bearer " + token2))
                .andExpect(status().isNoContent());

        Restaurant updated = restaurantRepository.findById(restaurant.getId()).orElseThrow();
        assertThat(updated.getReviewCount()).isEqualTo(1);
        assertThat(updated.getAvgRating().doubleValue()).isEqualTo(4.0);
    }

    // ── Delete review — authorization ─────────────────────────────────────────

    @Test
    void deleteReview_byOwner_returns204() throws Exception {
        Long reviewId = postReview(userToken, restaurant.getId(), 4, null);

        mockMvc.perform(delete("/api/v1/restaurants/{rId}/reviews/{rvId}",
                        restaurant.getId(), reviewId)
                        .header("Authorization", "Bearer " + userToken))
                .andExpect(status().isNoContent());

        assertThat(reviewRepository.findById(reviewId)).isEmpty();
    }

    @Test
    void deleteReview_byOtherUser_returns401() throws Exception {
        Long reviewId = postReview(userToken, restaurant.getId(), 4, null);

        User anotherUser = userRepository.save(
                TestDataFactory.aUser("other@test.dev", passwordEncoder.encode(PASSWORD)));
        String otherToken = jwtTokenProvider.generateAccessToken(anotherUser);

        mockMvc.perform(delete("/api/v1/restaurants/{rId}/reviews/{rvId}",
                        restaurant.getId(), reviewId)
                        .header("Authorization", "Bearer " + otherToken))
                .andExpect(status().isUnauthorized());

        // Review must still exist
        assertThat(reviewRepository.findById(reviewId)).isPresent();
    }

    @Test
    void deleteReview_byAdmin_returns204() throws Exception {
        Long reviewId = postReview(userToken, restaurant.getId(), 4, null);

        User admin = userRepository.save(
                User.builder()
                        .email("admin@test.dev")
                        .passwordHash(passwordEncoder.encode(PASSWORD))
                        .displayName("Admin")
                        .role(Role.ADMIN)
                        .enabled(true)
                        .build()
        );
        String adminToken = jwtTokenProvider.generateAccessToken(admin);

        mockMvc.perform(delete("/api/v1/restaurants/{rId}/reviews/{rvId}",
                        restaurant.getId(), reviewId)
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isNoContent());

        assertThat(reviewRepository.findById(reviewId)).isEmpty();
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private Long postReview(String bearerToken, Long restaurantId,
                            int rating, Long foodId) throws Exception {
        String body = foodId == null
                ? """
                  {"rating":%d}
                  """.formatted(rating)
                : """
                  {"rating":%d,"foodId":%d}
                  """.formatted(rating, foodId);

        MvcResult result = mockMvc.perform(
                        post("/api/v1/restaurants/{id}/reviews", restaurantId)
                                .header("Authorization", "Bearer " + bearerToken)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(body))
                .andExpect(status().isCreated())
                .andReturn();

        JsonNode json = objectMapper.readTree(result.getResponse().getContentAsString());
        return json.at("/data/id").asLong();
    }
}
