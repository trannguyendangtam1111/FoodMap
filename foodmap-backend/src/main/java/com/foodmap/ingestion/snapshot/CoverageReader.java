package com.foodmap.ingestion.snapshot;

import com.foodmap.catalog.AreaPreset;

import java.util.Collection;
import java.util.List;
import java.util.Map;

/** Current food × area supply as the product counts it. Read-only. */
public interface CoverageReader {

    /** area key → food key → restaurants with that food inside the area's radius. */
    Map<String, Map<String, Integer>> read(List<AreaPreset> areas, Collection<String> foods);
}
