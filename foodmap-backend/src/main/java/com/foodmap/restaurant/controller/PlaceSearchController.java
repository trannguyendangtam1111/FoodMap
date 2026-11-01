package com.foodmap.restaurant.controller;

import com.foodmap.common.dto.ApiResponse;
import com.foodmap.common.dto.PageResponse;
import com.foodmap.restaurant.dto.PlaceMarkerDTO;
import com.foodmap.restaurant.service.PlaceSearchService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Local geo-search for map markers.
 *
 *   GET /api/v1/places/search
 *
 *   All params optional. Most natural combinations:
 *     - Map pan/zoom:   ?swLat=&swLng=&neLat=&neLng=&zoom=
 *     - "Near me":      ?lat=&lng=&zoom=&q=phở
 *     - Category page:  ?category=cafe&lat=&lng=
 *
 *   The endpoint NEVER calls Goong — it reads only from Postgres + PostGIS.
 *   Goong is the async ingestion source that populates the table this
 *   endpoint reads from (see KeywordFanoutService + IngestionWorker).
 */
@RestController
@RequestMapping("/api/v1/places")
@RequiredArgsConstructor
@Tag(name = "Places", description = "Local geo-search for map markers (PostGIS only)")
public class PlaceSearchController {

    private final PlaceSearchService placeSearchService;

    @GetMapping("/search")
    @Operation(summary = "Search places locally by viewport / center / keyword / category")
    public ResponseEntity<ApiResponse<PageResponse<PlaceMarkerDTO>>> search(
            // Viewport bbox (all four required together)
            @RequestParam(required = false) Double swLat,
            @RequestParam(required = false) Double swLng,
            @RequestParam(required = false) Double neLat,
            @RequestParam(required = false) Double neLng,
            // Center + zoom (radius mode)
            @RequestParam(required = false) Double lat,
            @RequestParam(required = false) Double lng,
            @RequestParam(required = false) Integer zoom,
            // Text + facet filters
            @RequestParam(required = false, name = "q") String keyword,
            @RequestParam(required = false) String category,
            // Pagination
            @RequestParam(defaultValue = "0")  int page,
            @RequestParam(defaultValue = "24") int size
    ) {
        PageResponse<PlaceMarkerDTO> result = placeSearchService.search(
            swLat, swLng, neLat, neLng,
            lat, lng, zoom,
            keyword, category,
            page, size
        );
        return ResponseEntity.ok(ApiResponse.ok(result));
    }
}
