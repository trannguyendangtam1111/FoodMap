package com.foodmap.restaurant.service;

import com.foodmap.common.dto.PageResponse;
import com.foodmap.common.exception.BadRequestException;
import com.foodmap.config.properties.AppProperties;
import com.foodmap.restaurant.dto.PlaceMarkerDTO;
import com.foodmap.restaurant.dto.PlaceMarkerProjection;
import com.foodmap.restaurant.repository.RestaurantRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * Local-first geo-search.
 *
 *   WHY LOCAL SERVING IS PREFERRED:
 *     - Latency: a PostGIS bbox/radius query returns in <10 ms for typical
 *       viewport sizes; a vendor (Goong) round-trip is 200-800 ms and is
 *       rate-limited.
 *     - Cost: every vendor call is metered; reads scale with traffic, not
 *       with our cache size.
 *     - Reliability: vendor outages must not take our search offline.
 *     - Ranking control: we own the ranking signal (distance + popularity
 *       + recency); vendors return only a relevance score we cannot tune.
 *
 *   WHY GOONG IS ASYNC INGESTION ONLY:
 *     - Goong has no Nearby Search endpoint. Discovery is done by a
 *       background worker that fans out keyword AutoComplete queries per
 *       geohash cell and enriches each unique place_id via Place Detail
 *       (see KeywordFanoutService + IngestionWorker).
 *     - That writes into the same restaurants table this service reads
 *       from — the user-facing read path never blocks on a Goong call.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class PlaceSearchService {

    private static final int DEFAULT_PAGE_SIZE = 24;
    private static final int MAX_PAGE_SIZE     = 100;

    /** Floor on the derived radius so very high zooms don't return zero hits. */
    private static final int MIN_DERIVED_RADIUS_M = 200;

    private final RestaurantRepository restaurantRepository;
    private final AppProperties appProperties;

    /**
     * Run the local search.
     *
     * Resolution rules:
     *   - bbox wins:    if all four bbox params are set, use them. The bbox
     *                   centroid is the implicit distance origin unless the
     *                   caller also supplied lat/lng (which then wins for
     *                   distance computation, not filtering).
     *   - radius mode:  if lat/lng + zoom are set (no bbox), derive radius
     *                   from zoom and apply ST_DWithin around lat/lng.
     *   - text only:    keyword/category with no spatial context is allowed;
     *                   ranking falls back to popularity/recency.
     */
    public PageResponse<PlaceMarkerDTO> search(
            Double swLat, Double swLng, Double neLat, Double neLng,
            Double lat, Double lng, Integer zoom,
            String keyword, String category,
            int page, int size) {

        boolean hasBbox = swLat != null && swLng != null && neLat != null && neLng != null;
        boolean hasCenter = lat != null && lng != null;

        if (hasBbox && (swLat >= neLat || swLng >= neLng)) {
            throw new BadRequestException("Invalid bbox: SW corner must be south-west of NE corner.");
        }

        // Resolve the distance origin. SQL always needs a non-null point so
        // ST_Distance can be computed for ranking; we either use the explicit
        // center, or fall back to the bbox centroid, or finally fall back to
        // a neutral point (0,0) which makes distance_score ≈ 0 contribute
        // nothing meaningful — the popularity/recency terms dominate.
        double centerLat;
        double centerLng;
        if (hasCenter) {
            centerLat = lat;
            centerLng = lng;
        } else if (hasBbox) {
            centerLat = (swLat + neLat) / 2.0;
            centerLng = (swLng + neLng) / 2.0;
        } else {
            centerLat = 0.0;
            centerLng = 0.0;
        }

        // Derive radius from zoom when no explicit bbox was supplied.
        // Capped above by appProperties.nearbyMaxRadiusM to bound query cost.
        Double radiusMeters = null;
        if (!hasBbox && hasCenter && zoom != null) {
            radiusMeters = deriveRadiusFromZoom(zoom);
        }

        String normalizedKeyword  = StringUtils.hasText(keyword)  ? keyword.trim()  : null;
        String normalizedCategory = StringUtils.hasText(category) ? category.trim() : null;

        int safeSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
        var pageable = PageRequest.of(Math.max(page, 0), safeSize);

        log.debug("places/search: hasBbox={} hasCenter={} zoom={} radius={} keyword='{}' category='{}' page={}/{}",
            hasBbox, hasCenter, zoom, radiusMeters, normalizedKeyword, normalizedCategory, page, safeSize);

        Page<PlaceMarkerProjection> result = restaurantRepository.searchPlaces(
            centerLat, centerLng,
            hasBbox ? swLat : null,
            hasBbox ? swLng : null,
            hasBbox ? neLat : null,
            hasBbox ? neLng : null,
            radiusMeters,
            normalizedCategory,
            normalizedKeyword,
            pageable
        );

        var markers = result.getContent().stream()
            .map(PlaceSearchService::toMarker)
            .toList();

        return PageResponse.of(markers, result);
    }

    /**
     * Web-Mercator-style zoom → radius mapping. Calibrated against typical
     * HCMC viewport sizes:
     *   zoom 10 → 5 km (clamped by nearbyMaxRadiusM)
     *   zoom 13 → ~3.1 km
     *   zoom 15 → ~780 m
     *   zoom 17 → ~195 m → clamped up to MIN_DERIVED_RADIUS_M
     */
    private double deriveRadiusFromZoom(int zoom) {
        double raw = 50000.0 / Math.pow(2.0, zoom - 9);
        int max = appProperties.getNearbyMaxRadiusM();
        return Math.max(MIN_DERIVED_RADIUS_M, Math.min(max, raw));
    }

    private static PlaceMarkerDTO toMarker(PlaceMarkerProjection p) {
        return PlaceMarkerDTO.builder()
            .id(p.getId())
            .name(p.getName())
            .latitude(p.getLatitude())
            .longitude(p.getLongitude())
            .avgRating(p.getAvgRating())
            .reviewCount(p.getReviewCount())
            .priceLevel(p.getPriceLevel())
            .cuisineType(p.getCuisineType())
            .distanceMeters(p.getDistanceMeters())
            .build();
    }

    public static int defaultPageSize() {
        return DEFAULT_PAGE_SIZE;
    }
}
