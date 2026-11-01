package com.foodmap.ingestion.snapshot;

import com.foodmap.catalog.AreaPreset;
import com.foodmap.decision.scope.LocationScope;
import com.foodmap.decision.service.FoodAvailabilityService;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Coverage exactly as users see it: FoodAvailabilityService's AREA snapshot
 * (eligible restaurants inside the area radius, classified by name and menu).
 * One radius query + one menu query per area. Read-only.
 */
@Component
@Profile("snapshot-ingest")
@RequiredArgsConstructor
public class FoodAvailabilityCoverageReader implements CoverageReader {

    private final FoodAvailabilityService availability;

    @Override
    public Map<String, Map<String, Integer>> read(List<AreaPreset> areas, Collection<String> foods) {
        Map<String, Map<String, Integer>> out = new LinkedHashMap<>();
        for (AreaPreset area : areas) {
            FoodAvailabilityService.Snapshot snapshot = availability.snapshot(new LocationScope.Area(area));
            Map<String, Integer> counts = new LinkedHashMap<>();
            for (String food : foods) counts.put(food, FoodAvailabilityService.countAt(snapshot, food, 0));
            out.put(area.key(), counts);
        }
        return out;
    }
}
