package com.foodmap.decision.service;

import com.foodmap.common.exception.BadRequestException;
import com.foodmap.config.properties.DecisionProperties;
import com.foodmap.decision.scope.LocationScope;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Validated input for a decision. Built from raw query strings so every
 * malformed value becomes a 400 with a clear message (Spring's own numeric
 * binding would surface type mismatches as 500 in this project). The scope
 * (lat/lng/radius/area) is parsed by decision.scope.LocationScopeParser.
 *
 * @param scope      where to look (USER, AREA or CITY_CENTER)
 * @param mood       requested mood, or null for "no preference"
 * @param excludeIds restaurant IDs that must never be returned (deduplicated)
 */
public record DecisionRequest(LocationScope scope, Mood mood, Set<Long> excludeIds) {

    public static DecisionRequest parse(LocationScope scope, String mood, String exclude, DecisionProperties props) {
        return new DecisionRequest(scope, parseMood(mood), parseExcludeIds(exclude, props.getMaxExcludeIds()));
    }

    private static Mood parseMood(String raw) {
        if (isBlank(raw)) return null;
        String slug = raw.trim();
        return Mood.fromSlug(slug).orElseThrow(() -> new BadRequestException(
            "Unsupported mood '" + slug + "'. Supported: " + Mood.supportedSlugs() + "."));
    }

    /** Restaurant IDs never to return: duplicates and empty tokens tolerated, at most {@code maxIds}. */
    public static Set<Long> parseExcludeIds(String raw, int maxIds) {
        if (isBlank(raw)) return Collections.emptySet();
        Set<Long> ids = new LinkedHashSet<>();   // duplicates collapse silently
        for (String token : raw.split(",")) {
            String t = token.trim();
            if (t.isEmpty()) continue;            // tolerate "12,,40" and trailing commas
            long id;
            try {
                id = Long.parseLong(t);
            } catch (NumberFormatException e) {
                throw new BadRequestException("exclude must be a comma-separated list of restaurant IDs.");
            }
            if (id <= 0) {
                throw new BadRequestException("exclude must contain positive restaurant IDs.");
            }
            ids.add(id);
        }
        if (ids.size() > maxIds) {
            throw new BadRequestException("exclude accepts at most " + maxIds + " IDs.");
        }
        return Collections.unmodifiableSet(ids);
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
