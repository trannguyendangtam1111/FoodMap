package com.foodmap.decision.service;

import java.util.Arrays;
import java.util.Optional;

/**
 * Controlled mood vocabulary for the decision API. V1 is deliberately tiny;
 * any other value is rejected with 400 rather than silently ignored, so a
 * client never believes a preference was applied when it wasn't.
 */
public enum Mood {

    /** Món nước — noodle soups and other broth-based dishes. */
    MON_NUOC("mon-nuoc", "món nước");

    private final String slug;
    private final String label;

    Mood(String slug, String label) {
        this.slug = slug;
        this.label = label;
    }

    public String slug()  { return slug; }
    public String label() { return label; }

    public static Optional<Mood> fromSlug(String slug) {
        return Arrays.stream(values()).filter(m -> m.slug.equals(slug)).findFirst();
    }

    public static String supportedSlugs() {
        return String.join(", ", Arrays.stream(values()).map(Mood::slug).toList());
    }
}
