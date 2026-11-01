package com.foodmap.decision.scope;

import com.foodmap.catalog.AreaPreset;
import com.foodmap.catalog.AreaPresets;
import com.foodmap.restaurant.dto.AreaSupplyRow;
import com.foodmap.restaurant.repository.RestaurantRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.LongSupplier;
import java.util.stream.Collectors;

/**
 * Whether each discovery area currently has enough restaurants to be offered.
 *
 * An area with fewer than {@link AreaPresets#minimumRestaurants()} active
 * restaurants inside its radius is unavailable: it is left out of the public
 * area list and rejected as {@code area=}. Nothing replaces it. Supply is
 * data, so it is re-measured — all areas in ONE query, at most once per
 * {@link #TTL_NANOS} — rather than fixed at startup (where the test profile
 * has no PostGIS, and data may change while the app runs).
 *
 * READ-ONLY. Logs area keys and counts only, never coordinates.
 */
@Slf4j
@Service
public class AreaSupplyService {

    static final long TTL_NANOS = 60L * 1_000_000_000L;

    private final RestaurantRepository restaurantRepository;
    private final AreaPresets presets;
    private final LongSupplier nanoTime;

    private Map<String, Integer> counts;          // guarded by this
    private long measuredAt;                      // guarded by this
    private List<String> lastUnavailable;         // guarded by this

    @Autowired
    public AreaSupplyService(RestaurantRepository restaurantRepository, AreaPresets presets) {
        this(restaurantRepository, presets, System::nanoTime);
    }

    AreaSupplyService(RestaurantRepository restaurantRepository, AreaPresets presets, LongSupplier nanoTime) {
        this.restaurantRepository = restaurantRepository;
        this.presets = presets;
        this.nanoTime = nanoTime;
    }

    /** Areas with enough restaurants, in display order. */
    public List<AreaPreset> availableAreas() {
        Map<String, Integer> supply = supply();
        return presets.all().stream().filter(a -> isAvailable(a, supply)).toList();
    }

    public boolean isAvailable(AreaPreset area) {
        return isAvailable(area, supply());
    }

    /** Restaurants inside each area's radius, by key (re-measured at most once per minute). */
    public synchronized Map<String, Integer> supply() {
        long now = nanoTime.getAsLong();
        if (counts == null || now - measuredAt >= TTL_NANOS) {
            counts = measure();
            measuredAt = now;
            report(counts);
        }
        return counts;
    }

    private boolean isAvailable(AreaPreset area, Map<String, Integer> supply) {
        return supply.getOrDefault(area.key(), 0) >= presets.minimumRestaurants();
    }

    private Map<String, Integer> measure() {
        List<AreaPreset> areas = presets.all();
        String lats = areas.stream().map(a -> Double.toString(a.centerLat())).collect(Collectors.joining(","));
        String lngs = areas.stream().map(a -> Double.toString(a.centerLng())).collect(Collectors.joining(","));
        String radii = areas.stream().map(a -> Integer.toString(a.radiusMeters())).collect(Collectors.joining(","));

        Map<Long, Long> byIndex = new HashMap<>();
        for (AreaSupplyRow row : restaurantRepository.countRestaurantsWithinAreas(lats, lngs, radii)) {
            byIndex.put(row.getAreaIndex(), row.getRestaurantCount());
        }
        Map<String, Integer> result = new LinkedHashMap<>();
        for (int i = 0; i < areas.size(); i++) {
            result.put(areas.get(i).key(), byIndex.getOrDefault((long) i + 1, 0L).intValue());
        }
        return Map.copyOf(result);
    }

    /** Says which areas are unavailable, once per change (keys and counts only). */
    private void report(Map<String, Integer> supply) {
        List<String> unavailable = presets.all().stream()
            .filter(a -> !isAvailable(a, supply))
            .map(a -> a.key() + "=" + supply.getOrDefault(a.key(), 0))
            .toList();
        if (unavailable.equals(lastUnavailable)) return;
        lastUnavailable = unavailable;
        if (unavailable.isEmpty()) {
            log.info("areas: all {} discovery areas have at least {} restaurants",
                presets.all().size(), presets.minimumRestaurants());
        } else {
            log.warn("areas: unavailable (fewer than {} restaurants), hidden from the catalog: {}",
                presets.minimumRestaurants(), String.join(", ", unavailable));
        }
    }
}
