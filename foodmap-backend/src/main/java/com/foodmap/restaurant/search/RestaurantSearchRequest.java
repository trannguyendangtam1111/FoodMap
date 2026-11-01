package com.foodmap.restaurant.search;

import com.foodmap.common.exception.BadRequestException;

/**
 * Validated input for GET /api/v1/restaurants/search. Built from raw strings
 * so malformed values become a 400 with a clear message (Spring's own numeric
 * binding surfaces type mismatches as 500 in this project).
 *
 * @param origin the user's real location, or null — used only to break ties
 */
public record RestaurantSearchRequest(String query, int page, int size, SearchRanker.Origin origin) {

    public static final int MAX_QUERY_LENGTH = 100;
    /** Same page-size ceiling as V1 search. */
    public static final int MAX_PAGE_SIZE = 20;

    public static RestaurantSearchRequest parse(String query, String page, String size, String lat, String lng) {
        if (query == null || query.isBlank()) {
            throw new BadRequestException("query must not be blank.");
        }
        String trimmed = query.strip();
        if (trimmed.length() > MAX_QUERY_LENGTH) {
            throw new BadRequestException("query must be at most " + MAX_QUERY_LENGTH + " characters.");
        }

        int p = parseInt("page", page, 0);
        if (p < 0) throw new BadRequestException("page must not be negative.");
        int s = Math.min(Math.max(parseInt("size", size, 6), 1), MAX_PAGE_SIZE);

        Double la = parseCoordinate("lat", lat, 90);
        Double ln = parseCoordinate("lng", lng, 180);
        if ((la == null) != (ln == null)) {
            throw new BadRequestException("lat and lng must be provided together.");
        }
        return new RestaurantSearchRequest(trimmed, p, s, la == null ? null : new SearchRanker.Origin(la, ln));
    }

    private static int parseInt(String name, String raw, int fallback) {
        if (raw == null || raw.isBlank()) return fallback;
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            throw new BadRequestException(name + " must be a whole number.");
        }
    }

    private static Double parseCoordinate(String name, String raw, int limit) {
        if (raw == null || raw.isBlank()) return null;
        double v;
        try {
            v = Double.parseDouble(raw.trim());
        } catch (NumberFormatException e) {
            throw new BadRequestException(name + " must be a number.");
        }
        if (!Double.isFinite(v) || Math.abs(v) > limit) {
            throw new BadRequestException(name + " must be between -" + limit + " and " + limit + ".");
        }
        return v;
    }
}
