package com.foodmap.decision.service;

import com.foodmap.catalog.FoodEntry;
import com.foodmap.catalog.FoodKind;
import com.foodmap.catalog.FoodVocabulary;
import com.foodmap.common.exception.BadRequestException;

import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Validated input for the food decisions. Every malformed value is a 400
 * with a clear message; nothing is silently ignored or substituted.
 *
 * @param kinds         food kinds to draw from
 * @param excludedFoods catalog food keys already shown (deduplicated)
 */
public record FoodDecisionRequest(Set<FoodKind> kinds, Set<String> excludedFoods) {

    /** At most this many distinct food keys in {@code exclude} (Master Specification §7.6). */
    public static final int MAX_EXCLUDED_FOODS = 30;

    /** The cà mèn draws meals only unless told otherwise. */
    public static final Set<FoodKind> DEFAULT_KINDS = Collections.unmodifiableSet(EnumSet.of(FoodKind.MEAL));

    public static FoodDecisionRequest parse(String kinds, String exclude, FoodVocabulary vocabulary) {
        return new FoodDecisionRequest(parseKinds(kinds, DEFAULT_KINDS), parseExcludedFoods(exclude, vocabulary));
    }

    /** "MEAL,DRINK" (any case); blank → {@code defaults}. Unknown kinds are a 400. */
    public static Set<FoodKind> parseKinds(String raw, Set<FoodKind> defaults) {
        if (raw == null || raw.isBlank()) return defaults;
        Set<FoodKind> kinds = EnumSet.noneOf(FoodKind.class);
        for (String token : raw.split(",")) {
            String t = token.trim();
            if (t.isEmpty()) continue;
            try {
                kinds.add(FoodKind.valueOf(t.toUpperCase(Locale.ROOT)));
            } catch (IllegalArgumentException e) {
                throw new BadRequestException("kinds accepts MEAL, DRINK and DESSERT.");
            }
        }
        if (kinds.isEmpty()) throw new BadRequestException("kinds accepts MEAL, DRINK and DESSERT.");
        return Collections.unmodifiableSet(kinds);
    }

    /**
     * Comma-separated catalog food keys. Duplicates collapse; a key the
     * catalog does not know is a 400 (a typo would otherwise silently fail
     * to exclude); more than {@value #MAX_EXCLUDED_FOODS} distinct keys is a 400.
     */
    static Set<String> parseExcludedFoods(String raw, FoodVocabulary vocabulary) {
        if (raw == null || raw.isBlank()) return Set.of();
        Set<String> keys = new LinkedHashSet<>();
        for (String token : raw.split(",")) {
            String key = token.trim();
            if (key.isEmpty()) continue;
            if (vocabulary.get(key).isEmpty()) {
                throw new BadRequestException("exclude contains an unknown food '" + key + "'.");
            }
            keys.add(key);
        }
        if (keys.size() > MAX_EXCLUDED_FOODS) {
            throw new BadRequestException("exclude accepts at most " + MAX_EXCLUDED_FOODS + " foods.");
        }
        return Collections.unmodifiableSet(keys);
    }

    /** The {@code food} of a restaurant decision: a discoverable catalog key, else a 400. */
    public static FoodEntry parseFood(String raw, FoodVocabulary vocabulary) {
        String key = raw.trim();
        return vocabulary.get(key)
            .filter(FoodEntry::discoverable)
            .orElseThrow(() -> new BadRequestException("Unknown food '" + key + "'."));
    }
}
