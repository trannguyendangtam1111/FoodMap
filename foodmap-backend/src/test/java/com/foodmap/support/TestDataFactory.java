package com.foodmap.support;

import com.foodmap.food.domain.Food;
import com.foodmap.restaurant.domain.Restaurant;
import com.foodmap.restaurant.domain.RestaurantFood;
import com.foodmap.user.domain.Role;
import com.foodmap.user.domain.User;

import java.math.BigDecimal;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Builds unsaved domain entities with sensible defaults.
 * Use a counter to guarantee unique slugs / goongPlaceIds per test run.
 */
public final class TestDataFactory {

    private static final AtomicLong seq = new AtomicLong();

    private TestDataFactory() {}

    public static User aUser(String email, String encodedPassword) {
        return User.builder()
                .email(email)
                .passwordHash(encodedPassword)
                .displayName("Test User")
                .role(Role.USER)
                .enabled(true)
                .build();
    }

    public static User anAdmin(String email, String encodedPassword) {
        return User.builder()
                .email(email)
                .passwordHash(encodedPassword)
                .displayName("Admin User")
                .role(Role.ADMIN)
                .enabled(true)
                .build();
    }

    public static Restaurant aRestaurant() {
        long n = seq.incrementAndGet();
        return Restaurant.builder()
                .goongPlaceId("test-goong-" + n)
                .name("Test Restaurant " + n)
                .address("123 Test Street, Ho Chi Minh City")
                .latitude(10.7769)
                .longitude(106.7009)
                .cuisineType("Vietnamese")
                .isActive(true)
                .build();
    }

    public static Food aFood() {
        long n = seq.incrementAndGet();
        return Food.builder()
                .name("Test Food " + n)
                .slug("test-food-" + n)
                .description("A test food item")
                .cuisine("Vietnamese")
                .build();
    }

    public static RestaurantFood linkFoodToRestaurant(Restaurant restaurant, Food food) {
        return RestaurantFood.builder()
                .restaurant(restaurant)
                .food(food)
                .price(new BigDecimal("50000"))
                .isSignature(false)
                .build();
    }
}
