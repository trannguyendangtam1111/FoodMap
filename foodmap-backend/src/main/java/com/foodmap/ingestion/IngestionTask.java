package com.foodmap.ingestion;

import com.foodmap.ingestion.domain.PlaceCell;

/**
 * One unit of work for the IngestionWorker.
 *
 * Carries the cell's center coordinates instead of re-decoding the geohash
 * in the worker — the producer (DiscoveryService or SeedCrawler) usually
 * already knows them.
 *
 * `source` is propagated through to the place_cells row so we can later
 * tell user-driven discovery apart from seed coverage in telemetry.
 */
public record IngestionTask(
    String cellKey,
    double centerLat,
    double centerLng,
    PlaceCell.Source source
) {}
