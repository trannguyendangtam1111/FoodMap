package com.foodmap.integration.goong;

import com.foodmap.integration.goong.dto.GoongPlaceDTO;
import com.foodmap.integration.goong.dto.GoongPrediction;

import java.util.List;
import java.util.Optional;

/**
 * Single boundary for all Goong calls.
 *
 * No other class in the codebase should know that Goong exists.
 * Service layer depends on this interface; tests use a fake implementation.
 */
public interface GoongClient {

    /**
     * Place AutoComplete with location bias. Used by the discovery
     * pipeline as the only supported way to surface place_ids near a
     * coordinate (Goong does not expose a Nearby Search endpoint).
     *
     * Returns an empty list (never null) when there are no predictions.
     * Throws GoongIntegrationException on transport/auth/rate failures.
     */
    List<GoongPrediction> autoComplete(String input, double lat, double lng, int radiusMeters);

    /**
     * Full place detail for a known goong_place_id.
     * Returns empty when Goong has no record for the id (treated as soft 404).
     * Throws GoongIntegrationException on transport/auth/rate failures.
     */
    Optional<GoongPlaceDTO> placeDetail(String goongPlaceId);
}
