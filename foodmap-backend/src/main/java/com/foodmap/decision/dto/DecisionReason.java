package com.foodmap.decision.dto;

/**
 * One factual reason behind a decision.
 *
 * @param code  stable machine-readable kind: DISTANCE, AREA, MOOD_NAME,
 *              MOOD_MENU, FOOD_NAME, FOOD_MENU, RATING
 * @param label short Vietnamese text, ready to display
 */
public record DecisionReason(String code, String label) {}
