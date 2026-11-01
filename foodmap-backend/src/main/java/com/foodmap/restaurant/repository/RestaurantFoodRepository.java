package com.foodmap.restaurant.repository;

import com.foodmap.restaurant.domain.RestaurantFood;
import com.foodmap.restaurant.dto.RestaurantDishNameProjection;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
public interface RestaurantFoodRepository extends JpaRepository<RestaurantFood, Long> {

    List<RestaurantFood> findByRestaurantId(Long restaurantId);

    Optional<RestaurantFood> findByRestaurantIdAndFoodId(Long restaurantId, Long foodId);

    boolean existsByRestaurantIdAndFoodId(Long restaurantId, Long foodId);

    void deleteByRestaurantIdAndFoodId(Long restaurantId, Long foodId);

    @Query("SELECT rf FROM RestaurantFood rf WHERE rf.restaurant.id = :restaurantId AND rf.isSignature = true")
    List<RestaurantFood> findSignatureDishesForRestaurant(@Param("restaurantId") Long restaurantId);

    /**
     * Dish names on the menus of many restaurants at once — one read-only
     * query for a whole candidate batch (avoids N+1 lazy loads). Restaurants
     * without a menu simply contribute no rows.
     */
    @Query("""
        SELECT rf.restaurant.id AS restaurantId, f.name AS dishName
        FROM RestaurantFood rf JOIN rf.food f
        WHERE rf.restaurant.id IN :restaurantIds
        """)
    List<RestaurantDishNameProjection> findDishNamesByRestaurantIds(
        @Param("restaurantIds") Collection<Long> restaurantIds);
}
