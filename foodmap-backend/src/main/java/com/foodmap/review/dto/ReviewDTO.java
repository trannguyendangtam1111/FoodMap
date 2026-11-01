package com.foodmap.review.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.foodmap.food.dto.FoodSummaryDTO;
import lombok.Builder;
import lombok.Getter;

import java.time.Instant;

@Getter
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ReviewDTO {
    private Long id;
    private Integer rating;
    private String comment;

    /** Non-null when the review targets a specific dish (not the restaurant overall). */
    private FoodSummaryDTO food;

    private ReviewUserDTO user;
    private Instant createdAt;
    private Instant updatedAt;
}
