package com.foodmap.ingestion;

import java.util.Set;

/**
 * Result of one cell's keyword fanout.
 *
 * Carries enough signal for the IngestionWorker to distinguish
 *   - "this cell is genuinely sparse" (some keywords succeeded but
 *     returned few/no place_ids) — low-density cooldown
 *   - "Goong is unhappy right now"  (every keyword call threw) —
 *     transient failure, retry with backoff
 * Without that distinction, both outcomes would look like a zero-place
 * crawl, and we'd either thrash Goong (retrying empty areas) or hide
 * outages (treating failures as fresh).
 */
public record FanoutResult(
    Set<String> placeIds,
    int keywordsAttempted,
    int keywordsFailed
) {

    /** True when at least one keyword call returned a usable response. */
    public boolean anyKeywordSucceeded() {
        return keywordsAttempted > keywordsFailed;
    }

    /** True when every keyword call threw — treat as a transient outage. */
    public boolean allKeywordsFailed() {
        return keywordsAttempted > 0 && keywordsFailed == keywordsAttempted;
    }

    /** API-usage signal — every attempted keyword is one AutoComplete call. */
    public int autoCompleteCalls() {
        return keywordsAttempted;
    }
}
