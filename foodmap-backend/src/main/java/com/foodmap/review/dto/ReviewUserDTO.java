package com.foodmap.review.dto;

import lombok.Builder;
import lombok.Getter;

/**
 * Lightweight user projection embedded in ReviewDTO.
 * Never exposes email or passwordHash — only what the review card needs.
 */
@Getter
@Builder
public class ReviewUserDTO {
    private Long id;
    private String displayName;
    private String avatarUrl;
}
