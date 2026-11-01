package com.foodmap.food.dto;

import lombok.Builder;
import lombok.Getter;

import java.util.List;

@Getter
@Builder
public class FoodSummaryDTO {
    private Long id;
    private String slug;
    private String name;
    private String cuisine;
    private String photoUrl;
    private List<String> tags;
}
