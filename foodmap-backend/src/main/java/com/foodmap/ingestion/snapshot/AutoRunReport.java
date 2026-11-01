package com.foodmap.ingestion.snapshot;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The {@code auto} section of an AUTO run's report: enough to audit the run
 * without its logs — why each pair was chosen or skipped, what each wave
 * spent and found, and coverage before / after.
 */
@Data
@NoArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class AutoRunReport {

    private Config config = new Config();
    private LedgerView ledgerBefore;
    private LedgerView ledgerAfter;

    /** Per food: the history the scores were computed from. */
    private Map<String, FoodHistory> history = new LinkedHashMap<>();

    /** area → food → restaurants inside the area radius (runtime rules), every discoverable food. */
    private Map<String, Map<String, Integer>> coverageBefore = new LinkedHashMap<>();
    private Map<String, Map<String, Integer>> coverageAfter;

    /** The initial ranking: eligible pairs, best first. */
    private List<Candidate> candidates = new ArrayList<>();
    /** Pairs not eligible at the start, with the reason. */
    private List<Skipped> skipped = new ArrayList<>();

    /** Dry run: the waves the run would start with (later waves are re-planned after re-measuring coverage). */
    private List<Wave> plannedWaves = new ArrayList<>();
    /** Executed waves, in order (index 0 = resumed pending work, when there was any). */
    private List<Wave> waves = new ArrayList<>();

    private List<String> resumedPairs = new ArrayList<>();
    /** Coverage that rose in an area this run did not probe for that food. */
    private List<CoverageChange> spillOver = new ArrayList<>();
    /** food × area pairs that went from below the target to at least the target. */
    private List<CoverageChange> pairsReachedTarget = new ArrayList<>();

    /** Dry run: worst case (6 per probe) and expected requests of the planned waves. */
    private Integer estimatedMaxRequests;
    private Double estimatedExpectedRequests;
    /** Ledger usage after this run if it spends its whole effective budget. */
    private Integer projectedLedgerUsage;

    /** The companion Markdown summary. */
    private String summaryFile;

    @Data
    @NoArgsConstructor
    public static class Config {
        private Integer configuredBudget;
        /** min(configured budget, daily ceiling − usage in the last 24 h): what the run may spend. */
        private int effectiveBudget;
        private int dailyCeiling;
        /** Kept free for retries: max(2, 5% of the effective budget). */
        private int retryMargin;
        private int target;
        private double minScore;
        private int waveSize;
        private int maxProbes;
        private List<String> excludeFoods = new ArrayList<>();
        private List<String> areas = new ArrayList<>();
        /** Weight of the overall average in each food's blended estimate (as if this many extra probes). */
        private double priorProbes;
        private double globalUsefulPerProbe;
        private double globalPlaceDetailPerProbe;
        private String database;
    }

    @Data @NoArgsConstructor @AllArgsConstructor
    public static class LedgerView {
        private int usage24h;
        private int completedPairs;
        private List<String> pendingPairs = new ArrayList<>();
    }

    @Data
    @NoArgsConstructor
    public static class FoodHistory {
        private int probes;
        private int requests;
        private int placeDetail;
        private int relevant;
        /** Relevant (MATCH) new restaurants inside the probed area's radius. */
        private int inside;
        private Double usefulPerProbe;
        private Double relevantPerRequest;
        private Double insidePerRequest;
        private Double placeDetailPerProbe;
        private double blendedUseful;
        private double blendedCost;
    }

    @Data
    @NoArgsConstructor
    public static class Candidate {
        private String pair;
        private String area;
        private String food;
        private String kind;
        private int count;
        /** supply band / kind, e.g. "0/MEAL" (band 0 = no restaurant yet, 1 = below target). */
        private String tier;
        private int foodProbes;
        private double expectedUseful;
        private double overlapDiscount;
        private double neighbourMultiplier;
        private double expectedCost;
        private double score;
    }

    @Data @NoArgsConstructor @AllArgsConstructor
    public static class Skipped {
        private String pair;
        private String reason;
    }

    @Data
    @NoArgsConstructor
    public static class Wave {
        private int index;
        /** The wave's area, or "(resume)" for wave 0. */
        private String area;
        private String reason;
        private List<WaveProbe> probes = new ArrayList<>();
        private Integer requestsUsed;
        private SnapshotRunReport.StopReason stopReason;
        private String dbCheck;
        /** The wave area's coverage of the probed foods after the wave. */
        private Map<String, Integer> coverageAfter;
    }

    @Data
    @NoArgsConstructor
    public static class WaveProbe {
        private Candidate planned;
        private int autocomplete;
        private int placeDetail;
        private int requests;
        private int predictions;
        private int newRestaurants;
        private int relevant;
        private int insideRelevant;
        private int rejected;
        /** Repeated within the query, or already found earlier in the run. */
        private int duplicates;
        /** Known by id or text, or rejected by an earlier run: resolved without Place Detail. */
        private int resolvedWithoutDetail;
        private int pending;
    }

    @Data @NoArgsConstructor @AllArgsConstructor
    public static class CoverageChange {
        private String area;
        private String food;
        private int before;
        private int after;
    }
}
