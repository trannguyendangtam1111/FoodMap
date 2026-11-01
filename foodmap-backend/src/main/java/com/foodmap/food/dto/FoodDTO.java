package com.foodmap.food.dto;

import lombok.Builder;
import lombok.Getter;

import java.math.BigDecimal;
import java.util.List;

@Getter
@Builder
public class FoodDTO {
    private Long id;
    private String slug;
    private String name;
    private String description;
    private String cuisine;
    private String photoUrl;
    private BigDecimal popularityScore;
    private List<String> tags;
}
