package com.foodmap.trending.domain;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum TrendingWindow {

    H24("24h"),
    D7("7d"),
    D30("30d");

    // API-facing string value used in query params and stored in the DB column
    private final String value;

    public static TrendingWindow fromValue(String value) {
        for (TrendingWindow w : values()) {
            if (w.value.equalsIgnoreCase(value)) return w;
        }
        throw new IllegalArgumentException("Unknown trending window: " + value);
    }
}
