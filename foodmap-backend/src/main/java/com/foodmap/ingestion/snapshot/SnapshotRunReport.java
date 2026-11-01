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
 * The JSON record of one snapshot run (./ingestion-runs/snapshot-<time>.json).
 * Also the input of a resume: partialCells keep the work still to do
 * (Autocomplete keywords not run, place_ids not resolved) and rejected keeps
 * the place_ids that must never be fetched again.
 */
@Data
@NoArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class SnapshotRunReport {

    /** AUTO: an executing AUTO run (an AUTO dry run is DRY_RUN with an {@code auto} section). */
    public enum Mode { DRY_RUN, EXECUTE, RESUME, AUTO }

    /** What the Autocomplete queries were: the keywords per map cell, or food labels per area. */
    public enum Discovery { CELLS, FOOD_PROBES }

    public enum StopReason {
        /** Planned only; no Goong request. */
        DRY_RUN,
        /** Every planned cell completed. */
        COMPLETED,
        /** Ran to the end, some cells left pending (e.g. a request failed). */
        COMPLETED_WITH_PENDING,
        /** The discovery share of the budget was used before every keyword ran. */
        DISCOVERY_CAP_REACHED,
        BUDGET_EXHAUSTED,
        RATE_LIMITED,
        PROVIDER_DEGRADED,
        /** Not started: a guard refused the run (see stopDetail). No Goong request. */
        REFUSED,
        /** Not started: another snapshot run holds the lock. No Goong request. */
        LOCKED,
        ERROR,
        /** AUTO: the rolling 24 h ledger leaves less than one probe (+ retry margin). No Goong request. */
        LEDGER_FULL,
        /** AUTO: no food × area pair is eligible and scores at least the minimum. */
        NO_ELIGIBLE_WORK,
        /** AUTO: app.snapshot.auto.max-probes probes ran. */
        MAX_PROBES_REACHED,
        /** AUTO: the database is not the allowed one, or a wave changed more than its expected inserts. */
        DB_SAFETY,
        /**
         * Written before the budget is armed and replaced by the final report.
         * A report left in this state (the process died) counts its whole
         * budget as used in the ledger.
         */
        IN_PROGRESS
    }

    /** Identifies the run across copies of its report (the ledger counts each run once). */
    private String runId;
    private String startedAt;
    private String finishedAt;
    private Mode mode;
    private StopReason stopReason;
    private String stopDetail;
    private String resumedFrom;

    private Discovery discovery = Discovery.CELLS;
    private List<String> areas = new ArrayList<>();
    private List<String> requestedCells = new ArrayList<>();
    private List<String> foods = new ArrayList<>();
    private Integer budget;
    private boolean large;
    private int discoveryCap;
    private int detailReserve;

    private Requests requests = new Requests();

    /** The ledger check before execution (also shown by a dry run). */
    private Ledger ledger;

    private int predictions;
    private int distinctIds;
    private int duplicates;
    private int knownById;
    private int knownByText;
    private int previouslyRejected;
    /** Distinct place_ids not known before the run (each needed a Place Detail). */
    private int unknownIds;

    private List<NewRestaurant> newRestaurants = new ArrayList<>();
    private List<UpdatedId> updatedIds = new ArrayList<>();
    private List<Rejected> rejected = new ArrayList<>();

    private List<PlannedCell> plannedCells = new ArrayList<>();
    private List<String> skippedCells = new ArrayList<>();
    private List<CompletedCell> completedCells = new ArrayList<>();
    /** Food probes whose query ran and whose places were all resolved (no place_cells row). */
    private List<String> completedProbes = new ArrayList<>();
    private List<PartialCell> partialCells = new ArrayList<>();
    private List<String> pendingIds = new ArrayList<>();
    private List<SnapshotPlan.Circle> region = new ArrayList<>();

    private Yield yield = new Yield();

    /** Per Autocomplete query: what it found and what it cost (place_ids are attributed to the query that saw them first). */
    private List<QueryResult> queries = new ArrayList<>();

    /** AUTO runs only: configuration, ranking, waves, coverage before/after. */
    private AutoRunReport auto;

    @Data
    @NoArgsConstructor
    public static class Requests {
        private int used;
        private int autocomplete;
        private int placeDetail;
        private int retries;
        private int failureCount;
        private Map<String, Integer> failures = new LinkedHashMap<>();
    }

    @Data
    @NoArgsConstructor
    public static class Ledger {
        /** Report files read (all *.json in the report directory). */
        private int reportsRead;
        /** Goong HTTP attempts recorded by runs that ended within the last 24 hours. */
        private int previous24hUsage;
        private int requestedBudget;
        private int projected24hUsage;
        private int dailyCeiling;
        /** Planned food × area pairs already probed (completed) by an earlier run. */
        private List<String> completedPairs = new ArrayList<>();
        /** Planned food × area pairs left pending by an earlier run (finish with resume). */
        private List<String> pendingPairs = new ArrayList<>();
        /** Report files that could not be read — execution fails closed. */
        private List<String> unreadableReports = new ArrayList<>();
        /** Whether execution is allowed (a dry run reports it; an executing run is refused when false). */
        private boolean allowed;
        private List<String> problems = new ArrayList<>();
    }

    @Data @NoArgsConstructor @AllArgsConstructor
    public static class NewRestaurant {
        private Long restaurantId;
        private String placeId;
        private String name;
        private Double lat;
        private Double lng;
        /** The query (unit + input) that first found it. */
        private String unitKey;
        private String query;
        /** Distance from that unit's centre (cell centre or area centre). */
        private Integer distanceMeters;
        /** The server's food classification of the stored name (FoodClassifier). */
        private List<String> foodKeys;
        /** Food probes: whether that classification contains the probed food; null for map cells. */
        private Boolean matchesProbeFood;
        /** Food probes: MATCH (has the probed food) or OTHER_FOOD; null for map cells. */
        private String relevance;
        /** Within the unit's runtime radius (area radius; 500 m for a map cell). */
        private Boolean insideRuntimeRadius;
        /** ADDRESS_WARD_ONLY, ADDRESS_NO_HOUSE_NUMBER, NEAR_EXISTING_25M, SAME_NAME_AS_KNOWN — report only, never merged. */
        private List<String> dataQualityFlags;
        /** Food probes: the query is in the name but the classifier does not give the probed food. */
        private Boolean classifierDisagrees;
    }

    @Data @NoArgsConstructor @AllArgsConstructor
    public static class UpdatedId {
        private Long restaurantId;
        private String previousPlaceId;
        private String placeId;
    }

    @Data @NoArgsConstructor @AllArgsConstructor
    public static class Rejected {
        private String placeId;
        /** NOT_FOUND, INVALID_PAYLOAD, INVALID_COORDINATES, OUTSIDE_VIETNAM, OUTSIDE_HCMC, OUTSIDE_REGION, NO_FOOD_MATCH */
        private String reason;
        private String name;
        private Double lat;
        private Double lng;
        /** NO_FOOD_MATCH: the query is in the name but the classifier found no catalog food. */
        private Boolean classifierDisagrees;
    }

    @Data @NoArgsConstructor @AllArgsConstructor
    public static class PlannedCell {
        private String cellKey;
        private double lat;
        private double lng;
        private SnapshotPlan.Priority priority;
        private List<String> keywords;
        private int radiusMeters;
        private String foodKey;
        private String areaKey;
    }

    @Data @NoArgsConstructor @AllArgsConstructor
    public static class CompletedCell {
        private String cellKey;
        /** Resolved restaurants within the cell's crawl radius (place_cells.place_count). */
        private int placesLocal;
    }

    @Data @NoArgsConstructor @AllArgsConstructor
    public static class PartialCell {
        private String cellKey;
        private double lat;
        private double lng;
        /** Autocomplete inputs not run yet. */
        private List<String> keywordsPending = new ArrayList<>();
        /** Discovered place_ids not resolved yet (no Autocomplete needed to resume them). */
        private List<SnapshotPlan.PendingPlace> pendingPlaces = new ArrayList<>();
        /** Local restaurants already resolved for this cell (carried into place_count on completion). */
        private int placesLocal;
        /** Autocomplete radius; 0 in reports written before food probes (= a map cell, 500 m). */
        private int radiusMeters;
        /** Food probes only. */
        private String foodKey;
        private String areaKey;
    }

    @Data
    @NoArgsConstructor
    public static class QueryResult {
        private String unitKey;
        /** The Autocomplete input. */
        private String query;
        /** Food probes only. */
        private String foodKey;
        private String area;
        /** Whether the Autocomplete call succeeded (false: not run, failed, or cut by a stop). */
        private boolean ran;
        /** Requests charged to this query: its Autocomplete attempts plus the Place Detail attempts for its place_ids. */
        private int autocompleteRequests;
        private int placeDetailRequests;
        private int requests;
        private int predictions;
        /** Distinct place_ids in this query's predictions. */
        private int distinctIds;
        /** Predictions repeating a place_id within this query. */
        private int duplicates;
        /** Distinct place_ids an earlier query of this run had already found (not charged here). */
        private int alreadySeenInRun;
        /** Distinct place_ids first found by this query. */
        private int newToRun;
        private int knownById;
        private int knownByText;
        private int previouslyRejected;
        /** First-found place_ids not known before the run (each one Place Detail). */
        private int unknownIds;
        private int newRestaurants;
        /** New restaurants classified with the probed food (MATCH) — what counts toward its coverage. */
        private int foodRelevantNew;
        /** New restaurants with other catalog foods only (saved; not the probed food's coverage). */
        private int otherFoodNew;
        /** Saved, but beyond the unit's runtime radius (inside the save region). */
        private int savedOutsideRuntimeRadius;
        private int updatedIds;
        private int rejected;
        private int rejectedNoFoodMatch;
        private Map<String, Integer> rejectedByReason = new LinkedHashMap<>();
        private int pending;
        private Double newPerRequest;
        private Double relevantPerRequest;
    }

    @Data
    @NoArgsConstructor
    public static class Yield {
        private Double newPerRequest;
        private Double newPerAutocomplete;
        private Double newPerPlaceDetail;
    }
}
