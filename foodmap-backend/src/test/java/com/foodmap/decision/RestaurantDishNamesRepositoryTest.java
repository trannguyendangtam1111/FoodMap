package com.foodmap.decision;

import com.foodmap.config.JpaAuditingConfig;
import com.foodmap.food.domain.Food;
import com.foodmap.food.repository.FoodRepository;
import com.foodmap.restaurant.domain.Restaurant;
import com.foodmap.restaurant.dto.RestaurantDishNameProjection;
import com.foodmap.restaurant.repository.RestaurantFoodRepository;
import com.foodmap.restaurant.repository.RestaurantRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;

import static com.foodmap.support.TestDataFactory.aFood;
import static com.foodmap.support.TestDataFactory.aRestaurant;
import static com.foodmap.support.TestDataFactory.linkFoodToRestaurant;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

/** The batch menu query used by the decision service's mood check. */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@Import(JpaAuditingConfig.class)
class RestaurantDishNamesRepositoryTest {

    @Autowired RestaurantRepository restaurantRepository;
    @Autowired FoodRepository foodRepository;
    @Autowired RestaurantFoodRepository restaurantFoodRepository;

    @Test
    void returnsDishNamesForTheRequestedRestaurantsOnly_inOneQuery() {
        Restaurant withMenu = restaurantRepository.save(aRestaurant());
        Restaurant other    = restaurantRepository.save(aRestaurant());
        Restaurant noMenu   = restaurantRepository.save(aRestaurant());

        Food canh = aFood(); canh.setName("Bánh Canh Cua");
        Food com  = aFood(); com.setName("Cơm Tấm");
        Food pho  = aFood(); pho.setName("Phở Gà");
        foodRepository.saveAll(List.of(canh, com, pho));

        restaurantFoodRepository.saveAll(List.of(
            linkFoodToRestaurant(withMenu, canh),
            linkFoodToRestaurant(withMenu, com),
            linkFoodToRestaurant(other, pho)));

        List<RestaurantDishNameProjection> rows =
            restaurantFoodRepository.findDishNamesByRestaurantIds(List.of(withMenu.getId(), noMenu.getId()));

        assertThat(rows)
            .extracting(RestaurantDishNameProjection::getRestaurantId, RestaurantDishNameProjection::getDishName)
            .containsExactlyInAnyOrder(
                tuple(withMenu.getId(), "Bánh Canh Cua"),
                tuple(withMenu.getId(), "Cơm Tấm"));
    }
}
