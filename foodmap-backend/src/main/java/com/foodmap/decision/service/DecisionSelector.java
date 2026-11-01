package com.foodmap.decision.service;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Random;
import java.util.random.RandomGenerator;

/**
 * Picks one restaurant from an already-ranked list.
 *
 * Only the top-N ranked candidates form the pool. Within it, weights fall
 * linearly with rank: in a pool of n, rank 1 has weight n, rank 2 has n−1 …
 * rank n has 1. The best match is the most likely pick, yet repeated calls
 * do not return the same place every time. That is the only purpose of the
 * randomness — there are no tiers, odds tables or "rare" outcomes.
 *
 * The random source is injectable, so tests can drive the draw exactly.
 */
@Component
public class DecisionSelector {

    private final RandomGenerator random;

    /** Production: java.util.Random is thread-safe for this shared singleton. */
    public DecisionSelector() {
        this(new Random());
    }

    DecisionSelector(RandomGenerator random) {
        this.random = random;
    }

    /** The top-N slice the draw happens in (the list must already be ranked). */
    public static <T> List<T> pool(List<T> ranked, int poolSize) {
        return ranked.subList(0, Math.min(Math.max(poolSize, 1), ranked.size()));
    }

    /** Weight of the candidate at 0-based {@code rankIndex} in a pool of {@code poolSize}. */
    public static int weight(int rankIndex, int poolSize) {
        return poolSize - rankIndex;
    }

    /** Draws one candidate from the top-{@code poolSize} of {@code ranked}. */
    public <T> T pick(List<T> ranked, int poolSize) {
        if (ranked.isEmpty()) throw new IllegalArgumentException("Nothing to pick from");
        List<T> pool = pool(ranked, poolSize);
        int n = pool.size();
        int totalWeight = n * (n + 1) / 2;
        return pickWithTicket(pool, random.nextInt(totalWeight));
    }

    /**
     * Draws one option with equal probability — the food decision. No
     * weights, so neither catalog order nor restaurant counts tilt the draw.
     */
    public <T> T pickUniformly(List<T> options) {
        if (options.isEmpty()) throw new IllegalArgumentException("Nothing to pick from");
        return options.get(random.nextInt(options.size()));
    }

    /** Deterministic core: walks cumulative weights until the ticket falls inside one. */
    static <T> T pickWithTicket(List<T> pool, int ticket) {
        int n = pool.size();
        int remaining = ticket;
        for (int i = 0; i < n; i++) {
            int w = weight(i, n);
            if (remaining < w) return pool.get(i);
            remaining -= w;
        }
        throw new IllegalArgumentException("Ticket " + ticket + " is outside the pool's total weight");
    }
}
