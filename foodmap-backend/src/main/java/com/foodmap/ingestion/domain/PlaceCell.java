package com.foodmap.ingestion.domain;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

/**
 * One row per ingested geohash-6 cell. The "coverage ledger" the
 * discovery pipeline consults to decide whether a viewport needs a
 * background sync.
 *
 * cell_key is the natural primary key — see V5 migration for the
 * full rationale. No surrogate id.
 *
 * No @CreatedDate / @LastModifiedDate — we manage last_indexed_at
 * explicitly because it has business meaning (when did we last hit
 * Goong for this cell?), not just audit meaning.
 */
@Entity
@Table(name = "place_cells")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PlaceCell {

    public enum Source {
        SEED,      // populated by startup HCMC seed crawl
        VIEWPORT,  // populated on-demand from a user pan
        MANUAL     // admin / debug trigger
    }

    /**
     * READY  — last attempt succeeded, or a transient retry is scheduled
     *          (next_eligible_at in the future, attempt_count > 0).
     * FAILED — retry budget exhausted; cell is in a long (24h) cooldown
     *          before the next attempt.
     */
    public enum Status {
        READY,
        FAILED
    }

    @Id
    @Column(name = "cell_key", length = 12, nullable = false)
    private String cellKey;

    @Column(name = "last_indexed_at", nullable = false)
    private Instant lastIndexedAt;

    /** Resolved places from the last successful crawl that lie within the cell's crawl radius. */
    @Column(name = "place_count", nullable = false)
    @Builder.Default
    private Integer placeCount = 0;

    @Column(name = "center_lat", nullable = false)
    private Double centerLat;

    @Column(name = "center_lng", nullable = false)
    private Double centerLng;

    @Enumerated(EnumType.STRING)
    @Column(name = "source", length = 20, nullable = false)
    @Builder.Default
    private Source source = Source.VIEWPORT;

    // ── Retry / cooldown bookkeeping (V7) ────────────────────────────────

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 16, nullable = false)
    @Builder.Default
    private Status status = Status.READY;

    /**
     * Cooldown gate. The discovery read path treats a cell as fresh
     * while next_eligible_at is in the future; only when it elapses
     * does the cell become re-enqueueable. Set explicitly per outcome
     * (success → 30d, low-density → 6h, transient failure → exponential
     * backoff, permanent failure → 24h).
     */
    @Column(name = "next_eligible_at", nullable = false)
    @Builder.Default
    private Instant nextEligibleAt = Instant.EPOCH;

    /**
     * Number of consecutive attempts that have failed. Reset to 0 on
     * the next successful crawl. Drives the exponential backoff.
     */
    @Column(name = "attempt_count", nullable = false)
    @Builder.Default
    private Integer attemptCount = 0;

    /** Timestamp of the most recent attempt — success OR failure. */
    @Column(name = "last_attempted_at")
    private Instant lastAttemptedAt;

    /** Short reason for the last failure; null on success. */
    @Column(name = "last_error", length = 255)
    private String lastError;
}
