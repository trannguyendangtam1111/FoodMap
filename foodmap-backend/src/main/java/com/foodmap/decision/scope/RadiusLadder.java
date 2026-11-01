package com.foodmap.decision.scope;

import java.util.List;

/**
 * The radii a scope may search, smallest first (Master Specification §8.3).
 * A search uses the first stage with a result; only the radius widens, never
 * the food or the scope, and the stage used is always reported.
 *
 *   USER         requested radius (default 1000) → min(radius × 3, 5000)
 *   CITY_CENTER  3000 → 5000
 *   AREA         the area's own radius; never widened
 *
 * @param stages strictly increasing positive radii in metres, base first
 */
public record RadiusLadder(List<Integer> stages) {

    public RadiusLadder {
        if (stages == null || stages.isEmpty()) throw new IllegalArgumentException("A ladder needs a stage");
        for (int i = 0; i < stages.size(); i++) {
            if (stages.get(i) <= 0 || (i > 0 && stages.get(i) <= stages.get(i - 1))) {
                throw new IllegalArgumentException("Stages must be positive and increasing: " + stages);
            }
        }
        stages = List.copyOf(stages);
    }

    /** One fixed radius: nothing to widen to. */
    public static RadiusLadder fixed(int radiusMeters) {
        return new RadiusLadder(List.of(radiusMeters));
    }

    /** {@code base}, then {@code widened} only when it is larger. */
    public static RadiusLadder widening(int base, int widened) {
        return widened > base ? new RadiusLadder(List.of(base, widened)) : fixed(base);
    }

    /** A user radius widened once by {@code factor}, never beyond {@code max}. */
    public static RadiusLadder user(int requested, int factor, int max) {
        return widening(requested, (int) Math.min((long) requested * factor, max));
    }

    public int base() {
        return stages.get(0);
    }

    public int widest() {
        return stages.get(stages.size() - 1);
    }

    public int widestStage() {
        return stages.size() - 1;
    }

    public int radiusAt(int stage) {
        return stages.get(stage);
    }
}
