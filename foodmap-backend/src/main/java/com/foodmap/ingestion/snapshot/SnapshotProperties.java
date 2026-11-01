package com.foodmap.ingestion.snapshot;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * One snapshot ingestion run, from the command line (app.snapshot.*).
 * Only read by SnapshotIngestionRunner, which exists under the
 * "snapshot-ingest" profile only.
 *
 *   java -jar backend.jar --spring.profiles.active=dev,snapshot-ingest \
 *     --app.snapshot.areas=binh-thanh --app.snapshot.max-cells=3 \
 *     --app.snapshot.budget=20 [--app.snapshot.execute=true]
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "app.snapshot")
public class SnapshotProperties {

    public static final int MAX_CELLS = 50;
    public static final int LARGE_BUDGET = 100;
    /** The Goong daily quota: a configured ceiling can never exceed it. */
    public static final int MAX_DAILY_CEILING = 1000;

    /** Area preset keys (area-catalog.yml) to take cells from. */
    private List<String> areas = new ArrayList<>();

    /** Explicit geohash-6 cell keys (optional; instead of, or with, areas). */
    private List<String> cells = new ArrayList<>();

    /**
     * Food probes instead of map cells: discoverable catalog food keys, each
     * one Autocomplete of its label at the (single) area's centre, in this order.
     */
    private List<String> foods = new ArrayList<>();

    /** Cells to plan (1..50). */
    private int maxCells = 3;

    /** Hard Goong HTTP-attempt budget for the run (required, 1..300; above 100 needs large=true). */
    private Integer budget;

    /** Explicit acknowledgement for a budget above 100. */
    private boolean large = false;

    /** False (default) = dry run: plan and report only, zero Goong requests. */
    private boolean execute = false;

    /** Also plan cells that were already crawled densely and recently. */
    private boolean includeCrawled = false;

    /** A previous run's report: finish its pending work (no new cells, no repeated Autocomplete). */
    private String resume;

    /**
     * AUTO mode (AutoRunCoordinator): the runner itself chooses food × area
     * probes from coverage, the ledger and probe history. Options under
     * app.snapshot.auto.* (AutoProperties). Still a dry run without execute.
     */
    private boolean auto = false;

    /** Where run reports are written — and the ledger read before every execution. */
    private String reportDir = "ingestion-runs";

    /**
     * Goong HTTP attempts allowed over a rolling 24 hours, across all runs
     * recorded in reportDir (used before + this run's budget). 1..1000.
     */
    private int dailyCeiling = 120;
}
