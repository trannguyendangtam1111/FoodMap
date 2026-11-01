package com.foodmap.catalog;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * The loaded food catalog: immutable, held in memory for the application's
 * lifetime. Built only by {@link FoodCatalogLoader}, which validates it.
 */
public final class FoodVocabulary {

    private final List<FoodEntry> entries;
    private final Map<String, FoodEntry> byKey;
    private final String version;

    FoodVocabulary(List<FoodEntry> entriesInCanonicalOrder, String version) {
        this.entries = List.copyOf(entriesInCanonicalOrder);
        this.byKey = this.entries.stream().collect(Collectors.toUnmodifiableMap(FoodEntry::key, Function.identity()));
        this.version = version;
    }

    /** Every entry: discoverable ones by {@code order}, then classification-only ones. */
    public List<FoodEntry> entries() {
        return entries;
    }

    public List<FoodEntry> discoverable() {
        return entries.stream().filter(FoodEntry::discoverable).toList();
    }

    public Optional<FoodEntry> get(String key) {
        return Optional.ofNullable(byKey.get(key));
    }

    /** Content hash of the catalog file; changes whenever the file changes. */
    public String version() {
        return version;
    }
}
