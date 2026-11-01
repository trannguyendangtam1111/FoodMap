package com.foodmap.catalog;

/**
 * One FoodMap discovery area: a server-owned search centre and radius. Not an
 * administrative boundary. Only {@code key}, {@code label} and {@code order}
 * are public; the centre and radius never leave the server.
 */
public record AreaPreset(String key, String label, int order, double centerLat, double centerLng, int radiusMeters) {

    /** Without the centre: area objects can reach logs and error messages. */
    @Override
    public String toString() {
        return "AreaPreset[" + key + "]";
    }
}
