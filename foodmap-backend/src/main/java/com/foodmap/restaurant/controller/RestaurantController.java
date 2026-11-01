package com.foodmap.restaurant.controller;

import com.foodmap.common.dto.ApiResponse;
import com.foodmap.common.dto.PageResponse;
import com.foodmap.common.exception.BadRequestException;
import com.foodmap.restaurant.dto.DiscoveryResult;
import com.foodmap.restaurant.dto.RestaurantDetailDTO;
import com.foodmap.restaurant.dto.RestaurantSummaryDTO;
import com.foodmap.restaurant.search.RestaurantSearchRequest;
import com.foodmap.restaurant.search.RestaurantSearchService;
import com.foodmap.restaurant.service.DiscoveryService;
import com.foodmap.restaurant.service.RestaurantService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Discovery endpoints. The viewport/nearby routes go through
 * DiscoveryService (local-first reads + async cell ingestion). Search is
 * strictly read-only (RestaurantSearchService) and never ingests. Per-id
 * and per-food lookups stay on RestaurantService — those don't have a
 * spatial dimension to ingest.
 *
 * The X-Indexing-Cells response header surfaces how many geohash-6
 * cells this request enqueued for background sync. The frontend uses
 * it to show a non-blocking "indexing this area…" hint.
 */
@RestController
@RequestMapping("/api/v1/restaurants")
@RequiredArgsConstructor
@Tag(name = "Restaurants", description = "Restaurant discovery and detail endpoints")
public class RestaurantController {

    private static final String INDEXING_HEADER = "X-Indexing-Cells";

    private final DiscoveryService discoveryService;
    private final RestaurantService restaurantService;
    private final RestaurantSearchService restaurantSearchService;

    /**
     * GET /api/v1/restaurants/viewport?swLat=&swLng=&neLat=&neLng=
     *
     * Primary map-loading endpoint. Returns DB rows in the viewport and
     * (asynchronously) enqueues any stale/unseen cells for ingestion.
     */
    @GetMapping("/viewport")
    @Operation(summary = "Get restaurants in a map viewport bounding box")
    public ResponseEntity<ApiResponse<List<RestaurantSummaryDTO>>> getViewport(
            @RequestParam double swLat,
            @RequestParam double swLng,
            @RequestParam double neLat,
            @RequestParam double neLng
    ) {
        if (swLat >= neLat || swLng >= neLng) {
            throw new BadRequestException("Invalid viewport: SW corner must be south-west of NE corner.");
        }
        DiscoveryResult<RestaurantSummaryDTO> result =
            discoveryService.getViewport(swLat, swLng, neLat, neLng);

        return ResponseEntity.ok()
            .header(INDEXING_HEADER, String.valueOf(result.cellsEnqueued()))
            .body(ApiResponse.ok(result.items()));
    }

    /**
     * GET /api/v1/restaurants/nearby?lat=&lng=&radius=1500
     */
    @GetMapping("/nearby")
    @Operation(summary = "Get restaurants near a point")
    public ResponseEntity<ApiResponse<List<RestaurantSummaryDTO>>> getNearby(
            @RequestParam double lat,
            @RequestParam double lng,
            @RequestParam(defaultValue = "1500") double radius
    ) {
        if (radius <= 0) {
            throw new BadRequestException("radius must be positive.");
        }
        DiscoveryResult<RestaurantSummaryDTO> result =
            discoveryService.getNearby(lat, lng, radius);

        return ResponseEntity.ok()
            .header(INDEXING_HEADER, String.valueOf(result.cellsEnqueued()))
            .body(ApiResponse.ok(result.items()));
    }

    /**
     * GET /api/v1/restaurants/search?query=phở&page=0&size=6[&lat=&lng=]
     *
     * Read-only: no ingestion, no provider call, no write. Relevance follows
     * the V2 search contract (see SearchRanker). `lat`/`lng` — only the
     * user's real location — break ties between equally relevant results.
     * X-Indexing-Cells is always 0 here and is kept for client compatibility.
     */
    @GetMapping("/search")
    @Operation(summary = "Search restaurants by name, menu dish or address (read-only)")
    public ResponseEntity<ApiResponse<PageResponse<RestaurantSummaryDTO>>> search(
            @RequestParam(required = false) String query,
            @RequestParam(required = false) String page,
            @RequestParam(required = false) String size,
            @RequestParam(required = false) String lat,
            @RequestParam(required = false) String lng
    ) {
        RestaurantSearchRequest request = RestaurantSearchRequest.parse(query, page, size, lat, lng);
        return ResponseEntity.ok()
            .header(INDEXING_HEADER, "0")
            .body(ApiResponse.ok(restaurantSearchService.search(request)));
    }

    /**
     * GET /api/v1/restaurants/{id}
     */
    @GetMapping("/{id}")
    @Operation(summary = "Get restaurant detail by ID")
    public ResponseEntity<ApiResponse<RestaurantDetailDTO>> getById(@PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.ok(restaurantService.getById(id)));
    }
}
