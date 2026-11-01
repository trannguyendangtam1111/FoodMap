package com.foodmap.restaurant.mapper;

import com.foodmap.food.mapper.FoodMapper;
import com.foodmap.food.match.FoodClassification;
import com.foodmap.restaurant.domain.Restaurant;
import com.foodmap.restaurant.dto.RestaurantDetailDTO;
import com.foodmap.restaurant.dto.RestaurantFoodDTO;
import com.foodmap.restaurant.dto.RestaurantSummaryDTO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.stream.Collectors;

@Component
@RequiredArgsConstructor
public class RestaurantMapper {

    private final FoodMapper foodMapper;

    public RestaurantSummaryDTO toSummary(Restaurant r) {
        return toSummary(r, null);
    }

    /** Summary with the server food classification (null → no food fields). */
    public RestaurantSummaryDTO toSummary(Restaurant r, FoodClassification food) {
        return RestaurantSummaryDTO.builder()
                .id(r.getId())
                .name(r.getName())
                .address(r.getAddress())
                .latitude(r.getLatitude())
                .longitude(r.getLongitude())
                .avgRating(r.getAvgRating())
                .reviewCount(r.getReviewCount())
                .priceLevel(r.getPriceLevel())
                .cuisineType(r.getCuisineType())
                .photos(r.getPhotos())
                .foodKeys(food == null ? null : food.foodKeys())
                .category(food == null ? null : food.category())
                .build();
    }

    public RestaurantDetailDTO toDetail(Restaurant r) {
        return toDetail(r, null);
    }

    /** Detail with the server food classification (null → no food fields). */
    public RestaurantDetailDTO toDetail(Restaurant r, FoodClassification food) {
        return RestaurantDetailDTO.builder()
                .id(r.getId())
                .name(r.getName())
                .address(r.getAddress())
                .latitude(r.getLatitude())
                .longitude(r.getLongitude())
                .avgRating(r.getAvgRating())
                .reviewCount(r.getReviewCount())
                .priceLevel(r.getPriceLevel())
                .cuisineType(r.getCuisineType())
                .photos(r.getPhotos())
                .phone(r.getPhone())
                .website(r.getWebsite())
                .openingHours(r.getOpeningHours())
                .foodKeys(food == null ? null : food.foodKeys())
                .category(food == null ? null : food.category())
                .restaurantFoods(r.getRestaurantFoods().stream()
                        .map(rf -> RestaurantFoodDTO.builder()
                                .id(rf.getId())
                                .food(foodMapper.toSummary(rf.getFood()))
                                .price(rf.getPrice())
                                .signature(rf.isSignature())
                                .build())
                        .collect(Collectors.toList()))
                .build();
    }
}
