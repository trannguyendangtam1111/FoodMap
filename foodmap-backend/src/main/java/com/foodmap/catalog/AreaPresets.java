package com.foodmap.catalog;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * The loaded area catalog: immutable, held in memory for the application's
 * lifetime. Built only by {@link AreaCatalogLoader}, which validates it.
 * Whether an area currently has enough restaurants is data, not
 * configuration — see decision.scope.AreaSupplyService.
 */
public final class AreaPresets {

    private final List<AreaPreset> areas;
    private final Map<String, AreaPreset> byKey;
    private final int minimumRestaurants;
    private final String version;

    AreaPresets(List<AreaPreset> areasInDisplayOrder, int minimumRestaurants, String version) {
        this.areas = List.copyOf(areasInDisplayOrder);
        this.byKey = this.areas.stream().collect(Collectors.toUnmodifiableMap(AreaPreset::key, Function.identity()));
        this.minimumRestaurants = minimumRestaurants;
        this.version = version;
    }

    /** Every configured area, by display order. */
    public List<AreaPreset> all() {
        return areas;
    }

    public Optional<AreaPreset> get(String key) {
        return Optional.ofNullable(byKey.get(key));
    }

    /** An area with fewer restaurants inside its radius is unavailable. */
    public int minimumRestaurants() {
        return minimumRestaurants;
    }

    /** Content hash of the area file. */
    public String version() {
        return version;
    }
}
