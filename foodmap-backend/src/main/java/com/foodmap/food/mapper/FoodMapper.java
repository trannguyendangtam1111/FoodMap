package com.foodmap.food.mapper;

import com.foodmap.food.domain.Food;
import com.foodmap.food.dto.FoodDTO;
import com.foodmap.food.dto.FoodSummaryDTO;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.stream.Collectors;

@Component
public class FoodMapper {

    public FoodSummaryDTO toSummary(Food food) {
        return FoodSummaryDTO.builder()
                .id(food.getId())
                .slug(food.getSlug())
                .name(food.getName())
                .cuisine(food.getCuisine())
                .photoUrl(food.getPhotoUrl())
                .tags(food.getTags().stream()
                        .map(t -> t.getName())
                        .sorted()
                        .collect(Collectors.toList()))
                .build();
    }

    public FoodDTO toDTO(Food food) {
        return FoodDTO.builder()
                .id(food.getId())
                .slug(food.getSlug())
                .name(food.getName())
                .description(food.getDescription())
                .cuisine(food.getCuisine())
                .photoUrl(food.getPhotoUrl())
                .popularityScore(food.getPopularityScore())
                .tags(food.getTags().stream()
                        .map(t -> t.getName())
                        .sorted()
                        .collect(Collectors.toList()))
                .build();
    }
}
