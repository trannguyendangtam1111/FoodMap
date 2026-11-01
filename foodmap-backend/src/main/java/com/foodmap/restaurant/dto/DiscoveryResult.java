package com.foodmap.restaurant.dto;

import java.util.List;

/**
 * Wraps a discovery query result alongside ingestion telemetry.
 *
 * `cellsEnqueued` tells the controller how many geohash-6 cells were
 * newly enqueued for background sync because of this request. The
 * controller surfaces it on the X-Indexing-Cells response header so the
 * frontend can show a subtle "indexing this area…" hint without changing
 * the JSON response shape.
 */
public record DiscoveryResult<T>(
    List<T> items,
    int cellsEnqueued
) {}
