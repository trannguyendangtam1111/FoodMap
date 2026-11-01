package com.foodmap.ingestion.repository;

import com.foodmap.ingestion.domain.PlaceCell;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Collection;
import java.util.Set;

@Repository
public interface PlaceCellRepository extends JpaRepository<PlaceCell, String> {

    /**
     * Fast probe used by DiscoveryService on every viewport read.
     *
     * Returns the keys of cells currently in cooldown (next_eligible_at
     * is in the future — they are NOT due for ingestion yet). The caller
     * computes "cells needing ingestion" as
     *   (requestedCells - returnedFreshKeys)
     * keeping the read path purely set-arithmetic in Java.
     *
     * Index used: idx_place_cells_next_eligible_at + PK probe.
     */
    @Query("""
        SELECT pc.cellKey FROM PlaceCell pc
        WHERE pc.cellKey IN :keys
          AND pc.nextEligibleAt > :now
        """)
    Set<String> findFreshKeys(
        @Param("keys") Collection<String> keys,
        @Param("now")  Instant now
    );

    /**
     * Drives the recrawl scheduler — oldest cells first so the recrawl
     * naturally prioritises the staleness frontier. Pageable.size acts
     * as the daily refresh budget.
     */
    @Query("""
        SELECT pc FROM PlaceCell pc
        WHERE pc.lastIndexedAt < :threshold
        ORDER BY pc.lastIndexedAt ASC
        """)
    Page<PlaceCell> findStaleCells(@Param("threshold") Instant threshold, Pageable pageable);
}
