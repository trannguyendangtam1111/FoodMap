package com.foodmap.ingestion.snapshot;

import com.foodmap.catalog.FoodVocabulary;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * The only bridge from the server-owned food catalog to a snapshot run:
 * a discoverable food key becomes ONE Autocomplete query, its catalog label
 * (the name people put on shops: "Phở", "Cơm", "Lẩu"). Keywords, suppressors
 * and the rest of the classification rules stay inside the catalog.
 */
@Component
@Profile("snapshot-ingest")
@RequiredArgsConstructor
public class SnapshotFoodQueries {

    private final FoodVocabulary vocabulary;

    /** The probe for a discoverable food, or empty (unknown key, or classification-only). */
    public Optional<FoodProbe> probeFor(String foodKey) {
        return vocabulary.get(foodKey)
            .filter(e -> e.discoverable())
            .map(e -> new FoodProbe(e.key(), e.label()));
    }

    /** A food key and the Autocomplete input it becomes. */
    public record FoodProbe(String foodKey, String query) {}
}
