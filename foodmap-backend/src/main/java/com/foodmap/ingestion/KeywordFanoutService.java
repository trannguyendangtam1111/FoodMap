package com.foodmap.ingestion;

import com.foodmap.common.exception.GoongIntegrationException;
import com.foodmap.config.properties.IngestionProperties;
import com.foodmap.integration.goong.GoongClient;
import com.foodmap.integration.goong.dto.GoongPrediction;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Goong does not expose a Nearby Search endpoint. To discover place_ids
 * near a coordinate the pipeline fans out a curated keyword list against
 * Place AutoComplete with location bias, then deduplicates the resulting
 * predictions in-memory.
 *
 * Per-cell call budget: at most {@code maxKeywordsPerCell} AutoComplete
 * calls plus one Place Detail per unique place_id surfaced. The rate
 * limiter (shared GoongRateLimiter) enforces the global throughput cap;
 * this service only enforces fanout *width*.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KeywordFanoutService {

    private final GoongClient goongClient;
    private final IngestionProperties properties;

    /**
     * Discover unique Goong place_ids inside one geohash cell.
     *
     * Returns a {@link FanoutResult} carrying both the deduplicated set
     * of place_ids (insertion-ordered LinkedHashSet) and the keyword-
     * attempt counters needed by the worker to tell "empty area" from
     * "Goong outage". Never returns null.
     */
    public FanoutResult discoverPlaceIds(double centerLat, double centerLng, int radiusMeters) {
        List<String> keywords = properties.getKeywords();
        if (keywords == null || keywords.isEmpty()) {
            log.debug("No keywords configured — skipping fanout at ({}, {})", centerLat, centerLng);
            return new FanoutResult(Set.of(), 0, 0);
        }
        int cap = Math.min(properties.getMaxKeywordsPerCell(), keywords.size());
        Set<String> placeIds = new LinkedHashSet<>();
        int attempted = 0;
        int failed = 0;

        for (int i = 0; i < cap; i++) {
            String keyword = keywords.get(i);
            if (!StringUtils.hasText(keyword)) continue;
            attempted++;

            List<GoongPrediction> predictions;
            try {
                predictions = goongClient.autoComplete(keyword, centerLat, centerLng, radiusMeters);
            } catch (GoongIntegrationException e) {
                // One keyword failing must not abort the rest of the cell.
                failed++;
                log.warn("AutoComplete failed for '{}' at ({}, {}): {}",
                    keyword, centerLat, centerLng, e.getMessage());
                continue;
            }

            int before = placeIds.size();
            for (GoongPrediction p : predictions) {
                if (StringUtils.hasText(p.placeId())) {
                    placeIds.add(p.placeId());
                }
            }
            log.debug("Keyword '{}' at ({}, {}) → {} predictions, {} new place_ids",
                keyword, centerLat, centerLng, predictions.size(), placeIds.size() - before);
        }

        log.info("Cell ({}, {}) fanout: {}/{} keywords ok → {} unique place_ids",
            centerLat, centerLng, attempted - failed, attempted, placeIds.size());
        return new FanoutResult(placeIds, attempted, failed);
    }
}
