package com.foodmap.ingestion.snapshot;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.foodmap.common.exception.BudgetExhaustedException;
import com.foodmap.common.exception.GoongIntegrationException;
import com.foodmap.common.exception.GoongRunAbortedException;
import com.foodmap.common.text.VietnameseText;
import com.foodmap.common.util.GeoUtils;
import com.foodmap.config.properties.IngestionProperties;
import com.foodmap.food.match.FoodClassifier;
import com.foodmap.ingestion.CellCoverageService;
import com.foodmap.ingestion.domain.PlaceCell;
import com.foodmap.integration.goong.GoongClient;
import com.foodmap.integration.goong.GoongRequestBudget;
import com.foodmap.integration.goong.dto.GoongPlaceDTO;
import com.foodmap.integration.goong.dto.GoongPrediction;
import com.foodmap.ingestion.snapshot.SnapshotRunReport.QueryResult;
import com.foodmap.ingestion.snapshot.SnapshotRunReport.StopReason;
import com.foodmap.restaurant.domain.Restaurant;
import com.foodmap.restaurant.dto.KnownPlaceRow;
import com.foodmap.restaurant.repository.RestaurantRepository;
import com.foodmap.restaurant.service.RestaurantSyncService;
import com.foodmap.restaurant.service.RestaurantSyncService.FetchResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

/**
 * One controlled snapshot ingestion run:
 *
 *   GUARDS → LOCK → PLAN → [dry run: REPORT]
 *          → DISCOVER (Autocomplete, capped at the planned calls)
 *          → DEDUPE (run-wide by place_id; known by id or exact name+address; earlier rejections)
 *          → DETAIL (Place Detail for the rest, until the budget is spent)
 *          → VALIDATE (coordinates, region) → save
 *          → RECORD (place_cells only for completed map cells) → REPORT
 *
 * The discovery units are map cells (the existing keywords per geohash-6
 * cell) or food probes (one catalog food label per query at one area's
 * centre). Everything after discovery is shared. Every place_id is
 * attributed to the query that found it first, so the report can say what
 * each query found and cost.
 *
 * Goong is reachable only between budget.arm and budget.disarm, i.e. only in
 * an executing run that passed every guard and holds the lock. A dry run
 * (the default) makes no Goong request and writes nothing but its report.
 */
@Slf4j
@Service
@Profile("snapshot-ingest")
@RequiredArgsConstructor
public class SnapshotIngestionService {

    /** Query label for place_ids carried over from an earlier run (resume): no Autocomplete in this run. */
    static final String RESUMED_QUERY = "(resumed)";

    /** Food probes: a fetched place with no catalog food is not saved. */
    static final String NO_FOOD_MATCH = "NO_FOOD_MATCH";
    static final String RELEVANCE_MATCH = "MATCH";
    static final String RELEVANCE_OTHER_FOOD = "OTHER_FOOD";
    static final int NEAR_METERS = 25;

    private final SnapshotPlanner planner;
    private final GoongClient goongClient;
    private final GoongRequestBudget budget;
    private final RestaurantSyncService syncService;
    private final RestaurantRepository restaurantRepository;
    private final CellCoverageService cellCoverageService;
    private final IngestionProperties ingestionProperties;
    private final SnapshotRunLock runLock;
    private final ObjectMapper objectMapper;
    private final FoodClassifier foodClassifier;

    /**
     * @param environmentProblems guard failures found by the caller (e.g. a web server running)
     * @return the report, already written to the report directory
     */
    public SnapshotRunReport run(SnapshotProperties p, List<String> environmentProblems) {
        SnapshotReportStore store = new SnapshotReportStore(objectMapper, Path.of(p.getReportDir()));
        SnapshotRunReport report = new SnapshotRunReport();
        report.setRunId(UUID.randomUUID().toString());
        report.setStartedAt(Instant.now().toString());
        report.setMode(p.getResume() != null ? SnapshotRunReport.Mode.RESUME
            : p.isExecute() ? SnapshotRunReport.Mode.EXECUTE : SnapshotRunReport.Mode.DRY_RUN);
        report.setDiscovery(p.getFoods().isEmpty() ? SnapshotRunReport.Discovery.CELLS : SnapshotRunReport.Discovery.FOOD_PROBES);
        report.setAreas(List.copyOf(p.getAreas()));
        report.setRequestedCells(List.copyOf(p.getCells()));
        report.setFoods(List.copyOf(p.getFoods()));
        report.setBudget(p.getBudget());
        report.setLarge(p.isLarge());
        report.setResumedFrom(p.getResume());

        List<String> problems = new ArrayList<>(environmentProblems);
        problems.addAll(guardProblems(p));
        if (!problems.isEmpty()) {
            return finish(report, store, StopReason.REFUSED, String.join("; ", problems));
        }

        Optional<SnapshotRunLock.Handle> lock = runLock.tryAcquire();
        if (lock.isEmpty()) {
            return finish(report, store, StopReason.LOCKED, "another snapshot run holds the lock");
        }
        try (SnapshotRunLock.Handle ignored = lock.get()) {
            SnapshotPlan plan;
            try {
                plan = p.getResume() != null ? planner.planResume(store.read(Path.of(p.getResume())), p.getBudget())
                    : !p.getFoods().isEmpty() ? planner.planFoodProbes(p, p.getBudget())
                    : planner.plan(p, p.getBudget());
            } catch (IllegalArgumentException e) {
                return finish(report, store, StopReason.REFUSED, e.getMessage());
            }
            describePlan(report, plan, p.getBudget());

            // LEDGER: rolling 24 h usage + this budget vs the daily ceiling; pairs already probed or pending.
            SnapshotRunReport.Ledger ledger = checkLedger(p, plan, Instant.now());
            report.setLedger(ledger);
            if (!p.isExecute()) {
                return finish(report, store, StopReason.DRY_RUN, "plan only — add --app.snapshot.execute=true to run it; execution "
                    + (ledger.isAllowed() ? "would be allowed" : "would be refused: " + String.join("; ", ledger.getProblems())));
            }
            if (!ledger.isAllowed()) {
                return finish(report, store, StopReason.REFUSED, String.join("; ", ledger.getProblems()));
            }

            // The marker goes to disk before any Goong request: a run that dies still counts in the ledger.
            report.setStopReason(StopReason.IN_PROGRESS);
            Path reportFile = store.write(report);
            StopReason stop;
            try {
                budget.arm(p.getBudget());
                stop = execute(plan, report, store.loadRejectedIds());
            } catch (RuntimeException e) {
                log.error("Snapshot run failed unexpectedly", e);
                report.setStopDetail(e.getClass().getSimpleName() + ": " + e.getMessage());
                stop = StopReason.ERROR;
            } finally {
                copyRequests(report, budget.snapshot());
                budget.disarm();
            }
            return finish(report, store, stop, report.getStopDetail(), reportFile);
        }
    }

    // ── Ledger ───────────────────────────────────────────────────────────────

    SnapshotRunReport.Ledger checkLedger(SnapshotProperties p, SnapshotPlan plan, Instant now) {
        SnapshotLedger.State state;
        try {
            state = new SnapshotLedger(objectMapper, Path.of(p.getReportDir())).read(now);
        } catch (UncheckedIOException e) {   // the directory itself cannot be listed: fail closed
            state = new SnapshotLedger.State(0, 0, Set.of(), Map.of(), List.of(p.getReportDir() + " (" + e.getMessage() + ")"));
        }
        SnapshotRunReport.Ledger ledger = new SnapshotRunReport.Ledger();
        ledger.setReportsRead(state.reportsRead());
        ledger.setPrevious24hUsage(state.usageInWindow());
        ledger.setRequestedBudget(p.getBudget());
        ledger.setProjected24hUsage(state.usageInWindow() + p.getBudget());
        ledger.setDailyCeiling(p.getDailyCeiling());
        ledger.setUnreadableReports(new ArrayList<>(state.unreadable()));

        List<String> problems = new ArrayList<>();
        if (!state.unreadable().isEmpty()) {
            problems.add("cannot read snapshot report(s) " + String.join(", ", state.unreadable())
                + " — execution fails closed (Goong usage could be undercounted)");
        }
        if (ledger.getProjected24hUsage() > p.getDailyCeiling()) {
            problems.add("Goong usage in the last 24 h (" + state.usageInWindow() + ") + budget " + p.getBudget()
                + " = " + ledger.getProjected24hUsage() + " exceeds the daily ceiling " + p.getDailyCeiling());
        }
        boolean resume = p.getResume() != null;
        for (SnapshotPlan.Cell unit : plan.cells()) {
            if (!unit.isFoodProbe()) continue;
            String pair = unit.cellKey();
            if (state.completedPairs().contains(pair)) {
                ledger.getCompletedPairs().add(pair);
                problems.add(pair + " was already probed by an earlier run — not repeated");
            } else if (!resume && state.pendingPairs().containsKey(pair)) {
                ledger.getPendingPairs().add(pair);
                problems.add(pair + " is pending in " + state.pendingPairs().get(pair)
                    + " — finish it with app.snapshot.resume (its Autocomplete is not repeated)");
            }
        }
        ledger.setProblems(problems);
        ledger.setAllowed(problems.isEmpty());
        return ledger;
    }

    // ── Guards ───────────────────────────────────────────────────────────────

    static List<String> guardProblems(SnapshotProperties p, IngestionProperties ingestion) {
        List<String> problems = new ArrayList<>();
        Integer b = p.getBudget();
        if (b == null) {
            problems.add("app.snapshot.budget is required");
        } else if (b < 1 || b > GoongRequestBudget.MAX_LIMIT) {
            problems.add("app.snapshot.budget must be 1.." + GoongRequestBudget.MAX_LIMIT + " (was " + b + ")");
        } else if (b > SnapshotProperties.LARGE_BUDGET && !p.isLarge()) {
            problems.add("a budget above " + SnapshotProperties.LARGE_BUDGET + " needs app.snapshot.large=true");
        }
        if (p.getMaxCells() < 1 || p.getMaxCells() > SnapshotProperties.MAX_CELLS) {
            problems.add("app.snapshot.max-cells must be 1.." + SnapshotProperties.MAX_CELLS + " (was " + p.getMaxCells() + ")");
        }
        if (p.getDailyCeiling() < 1 || p.getDailyCeiling() > SnapshotProperties.MAX_DAILY_CEILING) {
            problems.add("app.snapshot.daily-ceiling must be 1.." + SnapshotProperties.MAX_DAILY_CEILING
                + " (was " + p.getDailyCeiling() + ")");
        }
        if (ingestion.isSeedOnStartup() || ingestion.isViewportEnqueueEnabled() || ingestion.isScheduledRecrawlEnabled()) {
            problems.add("automatic ingestion triggers must be off (seed-on-startup, viewport-enqueue-enabled, scheduled-recrawl-enabled)");
        }
        boolean hasTargets = !p.getAreas().isEmpty() || !p.getCells().isEmpty() || !p.getFoods().isEmpty();
        if (p.getResume() != null && hasTargets) {
            problems.add("app.snapshot.resume cannot be combined with areas, cells or foods");
        } else if (p.getResume() == null && !hasTargets) {
            problems.add("give app.snapshot.areas and/or app.snapshot.cells (or app.snapshot.resume)");
        }
        if (!p.getFoods().isEmpty() && p.getResume() == null) {
            if (p.getAreas().size() != 1) problems.add("food probes need exactly one app.snapshot.areas");
            if (!p.getCells().isEmpty()) problems.add("food probes cannot be combined with app.snapshot.cells");
        }
        return problems;
    }

    private List<String> guardProblems(SnapshotProperties p) {
        return guardProblems(p, ingestionProperties);
    }

    // ── Execute ──────────────────────────────────────────────────────────────

    /**
     * Called by an AUTO run after a wave's Autocomplete + dedupe, before any
     * Place Detail: {@code pending} = every unit of the wave with its queries
     * not run and its place_ids not resolved yet (a checkpoint to persist).
     */
    interface WaveListener {
        WaveListener NONE = pending -> { };

        void afterDiscovery(List<SnapshotRunReport.PartialCell> pending);
    }

    private StopReason execute(SnapshotPlan plan, SnapshotRunReport report, Set<String> previouslyRejected) {
        return executeWave(plan, report, previouslyRejected, loadKnown(), WaveListener.NONE);
    }

    /** The stored restaurants, by place_id and by exact name + address. */
    Known loadKnown() {
        return Known.load(restaurantRepository.findKnownPlaces());
    }

    /**
     * Runs one plan with an armed budget: DISCOVER → DEDUPE → [listener] →
     * DETAIL → VALIDATE/save → RECORD, into {@code report}. An AUTO run calls
     * it once per wave with the same {@code known} (places created or
     * re-keyed by earlier waves become known by id) and {@code previouslyRejected}.
     */
    StopReason executeWave(SnapshotPlan plan, SnapshotRunReport report, Set<String> previouslyRejected,
                           Known known, WaveListener listener) {
        Map<String, UnitState> units = new LinkedHashMap<>();
        Map<String, QueryResult> queries = new LinkedHashMap<>();             // in plan order
        Map<String, SnapshotPlan.PendingPlace> candidates = new LinkedHashMap<>();   // run-wide, first seen first
        Map<String, String> firstQuery = new HashMap<>();                     // place_id → query that found it first
        for (SnapshotPlan.Cell cell : plan.cells()) {
            UnitState state = new UnitState(cell);
            units.put(cell.cellKey(), state);
            for (String keyword : cell.keywords()) {
                queries.put(queryKey(cell, keyword), newQuery(cell, keyword));
            }
            if (!cell.pendingPlaceIds().isEmpty()) {
                String resumed = queryKey(cell, RESUMED_QUERY);
                QueryResult q = queries.computeIfAbsent(resumed, k -> newQuery(cell, RESUMED_QUERY));
                for (SnapshotPlan.PendingPlace pending : cell.pendingPlaceIds()) {
                    state.placeIds.add(pending.placeId());
                    if (candidates.putIfAbsent(pending.placeId(), pending) == null) {
                        firstQuery.put(pending.placeId(), resumed);
                        q.setDistinctIds(q.getDistinctIds() + 1);
                        q.setNewToRun(q.getNewToRun() + 1);
                    }
                }
            }
        }

        // ── DISCOVER: never more than the planned calls (the rest is the Place Detail reserve)
        StopReason stop = null;
        int discovered = 0;
        int withId = 0;
        int discoveredDistinct = 0;
        budget.beginPhase("discovery", plan.discoveryCalls());
        try {
            discovery:
            for (UnitState state : units.values()) {
                for (String keyword : List.copyOf(state.keywordsPending)) {
                    QueryResult q = queries.get(queryKey(state.cell, keyword));
                    List<GoongPrediction> predictions;
                    int before = budget.used();
                    try {
                        predictions = goongClient.autoComplete(keyword, state.cell.lat(), state.cell.lng(),
                            state.cell.radiusMeters());
                    } catch (BudgetExhaustedException e) {
                        stop = e.isPhaseLimited() ? StopReason.DISCOVERY_CAP_REACHED : StopReason.BUDGET_EXHAUSTED;
                        report.setStopDetail(e.getMessage());
                        break discovery;
                    } catch (GoongRunAbortedException e) {
                        stop = abortReason(e);
                        report.setStopDetail(e.getMessage());
                        break discovery;
                    } catch (GoongIntegrationException e) {
                        log.warn("Autocomplete '{}' failed for {}: {}", keyword, state.cell.cellKey(), e.getMessage());
                        continue;   // the keyword stays pending; the unit stays partial
                    } finally {
                        q.setAutocompleteRequests(q.getAutocompleteRequests() + budget.used() - before);
                    }
                    state.keywordsPending.remove(keyword);
                    q.setRan(true);
                    q.setPredictions(predictions.size());
                    discovered += predictions.size();
                    Set<String> inQuery = new LinkedHashSet<>();
                    int queryWithId = 0;
                    for (GoongPrediction prediction : predictions) {
                        if (!StringUtils.hasText(prediction.placeId())) continue;
                        withId++;
                        queryWithId++;
                        state.placeIds.add(prediction.placeId());
                        if (!inQuery.add(prediction.placeId())) continue;
                        if (candidates.putIfAbsent(prediction.placeId(), new SnapshotPlan.PendingPlace(
                                prediction.placeId(), prediction.mainText(), prediction.secondaryText())) == null) {
                            discoveredDistinct++;
                            firstQuery.put(prediction.placeId(), queryKey(state.cell, keyword));
                            q.setNewToRun(q.getNewToRun() + 1);
                        } else {
                            q.setAlreadySeenInRun(q.getAlreadySeenInRun() + 1);
                        }
                    }
                    q.setDistinctIds(inQuery.size());
                    q.setDuplicates(queryWithId - inQuery.size());
                }
            }
        } finally {
            budget.endPhase();
        }
        report.setPredictions(discovered);
        report.setDistinctIds(candidates.size());
        report.setDuplicates(withId - discoveredDistinct);   // predictions whose place_id was already collected

        // ── DEDUPE: known places resolve without Goong, even after a stop
        Map<String, Resolved> resolved = new HashMap<>();
        List<SnapshotPlan.PendingPlace> toFetch = new ArrayList<>();
        for (SnapshotPlan.PendingPlace place : candidates.values()) {
            String id = place.placeId();
            QueryResult q = queries.get(firstQuery.get(id));
            KnownPlaceRow byId = known.byPlaceId.get(id);
            Optional<KnownPlaceRow> byText = byId == null ? known.byText(place) : Optional.empty();
            if (byId != null) {
                report.setKnownById(report.getKnownById() + 1);
                q.setKnownById(q.getKnownById() + 1);
                resolved.put(id, Resolved.at(byId.getLatitude(), byId.getLongitude()));
            } else if (byText.isPresent()) {
                report.setKnownByText(report.getKnownByText() + 1);
                q.setKnownByText(q.getKnownByText() + 1);
                resolved.put(id, Resolved.at(byText.get().getLatitude(), byText.get().getLongitude()));
            } else if (previouslyRejected.contains(id)) {
                report.setPreviouslyRejected(report.getPreviouslyRejected() + 1);
                q.setPreviouslyRejected(q.getPreviouslyRejected() + 1);
                resolved.put(id, Resolved.NOT_A_RESTAURANT);
            } else {
                report.setUnknownIds(report.getUnknownIds() + 1);
                q.setUnknownIds(q.getUnknownIds() + 1);
                toFetch.add(place);
            }
        }

        // ── CHECKPOINT (AUTO): the discovered place_ids are on disk before any Place Detail
        listener.afterDiscovery(units.values().stream()
            .filter(s -> !s.keywordsPending.isEmpty() || s.placeIds.stream().anyMatch(id -> !resolved.containsKey(id)))
            .map(s -> new SnapshotRunReport.PartialCell(s.cell.cellKey(), s.cell.lat(), s.cell.lng(),
                new ArrayList<>(s.keywordsPending),
                new ArrayList<>(s.placeIds.stream().filter(id -> !resolved.containsKey(id)).map(candidates::get).toList()),
                s.cell.localSoFar(), s.cell.radiusMeters(), s.cell.foodKey(), s.cell.areaKey()))
            .toList());

        // ── DETAIL: each unknown place_id at most once, until the budget is spent
        boolean detailStopped = stop == StopReason.BUDGET_EXHAUSTED || stop == StopReason.RATE_LIMITED
            || stop == StopReason.PROVIDER_DEGRADED;
        if (!detailStopped) {
            for (SnapshotPlan.PendingPlace place : toFetch) {
                String id = place.placeId();
                String queryKey = firstQuery.get(id);
                QueryResult q = queries.get(queryKey);
                SnapshotPlan.Cell unit = units.get(unitOf(queryKey)).cell;
                AtomicReference<GoongPlaceDTO> fetched = new AtomicReference<>();
                FetchResult result;
                int before = budget.used();
                try {
                    result = syncService.fetchIfUnknown(id, dto -> {
                        fetched.set(dto);
                        Optional<String> invalid = SnapshotPlaceValidator.rejectReason(dto, plan.region());
                        if (invalid.isPresent() || !unit.isFoodProbe()) return invalid;
                        // Food probes only: a place with no catalog food at all is not saved.
                        return foodKeysOf(dto.name()).isEmpty() ? Optional.of(NO_FOOD_MATCH) : Optional.empty();
                    });
                } catch (BudgetExhaustedException e) {
                    stop = StopReason.BUDGET_EXHAUSTED;
                    report.setStopDetail(e.getMessage());
                    break;
                } catch (GoongRunAbortedException e) {
                    stop = abortReason(e);
                    report.setStopDetail(e.getMessage());
                    break;
                } catch (GoongIntegrationException e) {
                    log.warn("Place Detail failed for {}: {}", id, e.getMessage());
                    continue;   // stays pending
                } finally {
                    q.setPlaceDetailRequests(q.getPlaceDetailRequests() + budget.used() - before);
                }
                resolved.put(id, record(result, id, fetched.get(), unit, q, report, known));
            }
        }

        // ── RECORD: a unit is complete only when all its queries ran and all its places are resolved
        Set<String> pending = new LinkedHashSet<>();
        for (UnitState state : units.values()) {
            List<SnapshotPlan.PendingPlace> unresolved = state.placeIds.stream()
                .filter(id -> !resolved.containsKey(id))
                .map(candidates::get)
                .toList();
            int local = state.cell.localSoFar() + (int) state.placeIds.stream()
                .map(resolved::get)
                .filter(r -> r != null && r.isLocalTo(state.cell.lat(), state.cell.lng(), cellCoverageService))
                .count();
            if (state.keywordsPending.isEmpty() && unresolved.isEmpty()) {
                if (state.cell.isFoodProbe()) {
                    report.getCompletedProbes().add(state.cell.cellKey());   // not a map cell: no place_cells row
                } else {
                    cellCoverageService.recordCrawlSuccess(state.cell.cellKey(), state.cell.lat(), state.cell.lng(),
                        PlaceCell.Source.MANUAL, local);
                    report.getCompletedCells().add(new SnapshotRunReport.CompletedCell(state.cell.cellKey(), local));
                }
            } else {
                report.getPartialCells().add(new SnapshotRunReport.PartialCell(state.cell.cellKey(),
                    state.cell.lat(), state.cell.lng(), new ArrayList<>(state.keywordsPending), new ArrayList<>(unresolved),
                    local, state.cell.radiusMeters(), state.cell.foodKey(), state.cell.areaKey()));
                unresolved.forEach(u -> pending.add(u.placeId()));
            }
        }
        report.setPendingIds(new ArrayList<>(pending));
        pending.forEach(id -> {
            QueryResult q = queries.get(firstQuery.get(id));
            q.setPending(q.getPending() + 1);
        });
        queries.values().forEach(q -> {
            q.setRequests(q.getAutocompleteRequests() + q.getPlaceDetailRequests());
            q.setNewPerRequest(ratio(q.getNewRestaurants(), q.getRequests()));
            if (q.getFoodKey() != null) q.setRelevantPerRequest(ratio(q.getFoodRelevantNew(), q.getRequests()));
        });
        report.setQueries(new ArrayList<>(queries.values()));

        if (stop != null) return stop;
        return report.getPartialCells().isEmpty() ? StopReason.COMPLETED : StopReason.COMPLETED_WITH_PENDING;
    }

    private Resolved record(FetchResult result, String placeId, GoongPlaceDTO place, SnapshotPlan.Cell unit,
                            QueryResult q, SnapshotRunReport report, Known known) {
        return switch (result.status()) {
            case KNOWN -> {
                report.setKnownById(report.getKnownById() + 1);
                q.setKnownById(q.getKnownById() + 1);
                yield Resolved.of(result.restaurant());
            }
            case CREATED -> {
                Restaurant r = result.restaurant();
                List<String> foodKeys = foodKeysOf(r.getName());
                Integer distance = r.getLatitude() == null || r.getLongitude() == null ? null
                    : (int) Math.round(GeoUtils.distanceMeters(unit.lat(), unit.lng(), r.getLatitude(), r.getLongitude()));
                Boolean inside = distance == null ? null : distance <= unit.radiusMeters();
                String relevance = null;
                Boolean disagrees = null;
                if (unit.isFoodProbe()) {
                    boolean match = foodKeys.contains(unit.foodKey());
                    relevance = match ? RELEVANCE_MATCH : RELEVANCE_OTHER_FOOD;
                    disagrees = !match && nameContainsQuery(r.getName(), q.getQuery());
                    if (match) q.setFoodRelevantNew(q.getFoodRelevantNew() + 1);
                    else q.setOtherFoodNew(q.getOtherFoodNew() + 1);
                }
                if (Boolean.FALSE.equals(inside)) q.setSavedOutsideRuntimeRadius(q.getSavedOutsideRuntimeRadius() + 1);
                report.getNewRestaurants().add(new SnapshotRunReport.NewRestaurant(
                    r.getId(), placeId, r.getName(), r.getLatitude(), r.getLongitude(),
                    unit.cellKey(), q.getQuery(), distance, foodKeys,
                    unit.isFoodProbe() ? foodKeys.contains(unit.foodKey()) : null,
                    relevance, inside, known.qualityFlags(r), disagrees));
                known.add(r);
                q.setNewRestaurants(q.getNewRestaurants() + 1);
                yield Resolved.of(r);
            }
            case REKEYED -> {
                report.getUpdatedIds().add(new SnapshotRunReport.UpdatedId(
                    result.restaurant().getId(), result.previousPlaceId(), placeId));
                known.index(result.restaurant());
                q.setUpdatedIds(q.getUpdatedIds() + 1);
                yield Resolved.of(result.restaurant());
            }
            case NOT_FOUND -> reject(report, q, placeId, "NOT_FOUND", null);
            case INVALID -> reject(report, q, placeId, "INVALID_PAYLOAD", place);
            case REJECTED -> reject(report, q, placeId, result.rejectReason(), place);
        };
    }

    private static Resolved reject(SnapshotRunReport report, QueryResult q, String placeId, String reason, GoongPlaceDTO place) {
        Double lat = place == null || place.geometry() == null || place.geometry().location() == null ? null : place.geometry().location().lat();
        Double lng = place == null || place.geometry() == null || place.geometry().location() == null ? null : place.geometry().location().lng();
        String name = place == null ? null : place.name();
        Boolean disagrees = NO_FOOD_MATCH.equals(reason) ? nameContainsQuery(name, q.getQuery()) : null;
        report.getRejected().add(new SnapshotRunReport.Rejected(placeId, reason, name, lat, lng, disagrees));
        q.setRejected(q.getRejected() + 1);
        q.getRejectedByReason().merge(reason, 1, Integer::sum);
        if (NO_FOOD_MATCH.equals(reason)) q.setRejectedNoFoodMatch(q.getRejectedNoFoodMatch() + 1);
        return Resolved.NOT_A_RESTAURANT;
    }

    /** The server's classification of a place name (no menu yet for a new place). */
    private List<String> foodKeysOf(String name) {
        return foodClassifier.classify(name, null, List.of()).foodKeys();
    }

    /** The query's words appear, in order, as whole words of the name (accent-exact, case-insensitive). */
    static boolean nameContainsQuery(String name, String query) {
        if (name == null || query == null) return false;
        String n = " " + String.join(" ", VietnameseText.tokenize(name)) + " ";
        String qq = " " + String.join(" ", VietnameseText.tokenize(query)) + " ";
        return qq.trim().length() > 0 && n.contains(qq);
    }

    private static StopReason abortReason(GoongRunAbortedException e) {
        return e.getReason() == GoongRunAbortedException.Reason.RATE_LIMITED
            ? StopReason.RATE_LIMITED : StopReason.PROVIDER_DEGRADED;
    }

    private static String queryKey(SnapshotPlan.Cell cell, String keyword) {
        return cell.cellKey() + '\u0001' + keyword;
    }

    private static String unitOf(String queryKey) {
        return queryKey.substring(0, queryKey.indexOf('\u0001'));
    }

    private static QueryResult newQuery(SnapshotPlan.Cell cell, String keyword) {
        QueryResult q = new QueryResult();
        q.setUnitKey(cell.cellKey());
        q.setQuery(keyword);
        q.setFoodKey(cell.foodKey());
        q.setArea(cell.areaKey());
        return q;
    }

    // ── Report ───────────────────────────────────────────────────────────────

    private void describePlan(SnapshotRunReport report, SnapshotPlan plan, int b) {
        report.setDiscoveryCap(plan.discoveryCalls());
        report.setDetailReserve(b - plan.discoveryCalls());
        report.setRegion(new ArrayList<>(plan.region()));
        report.setSkippedCells(new ArrayList<>(plan.skipped()));
        if (plan.cells().stream().anyMatch(SnapshotPlan.Cell::isFoodProbe)) {
            report.setDiscovery(SnapshotRunReport.Discovery.FOOD_PROBES);
        }
        plan.cells().forEach(c -> report.getPlannedCells().add(new SnapshotRunReport.PlannedCell(
            c.cellKey(), c.lat(), c.lng(), c.priority(), c.keywords(), c.radiusMeters(), c.foodKey(), c.areaKey())));
    }

    static void copyRequests(SnapshotRunReport report, GoongRequestBudget.Snapshot spent) {
        SnapshotRunReport.Requests r = report.getRequests();
        r.setUsed(spent.used());
        r.setAutocomplete(spent.count("autoComplete"));
        r.setPlaceDetail(spent.count("placeDetail"));
        r.setRetries(spent.retries());
        r.setFailureCount(spent.failureCount());
        r.setFailures(new LinkedHashMap<>(spent.failures()));
        int created = report.getNewRestaurants().size();
        report.getYield().setNewPerRequest(ratio(created, r.getUsed()));
        report.getYield().setNewPerAutocomplete(ratio(created, r.getAutocomplete()));
        report.getYield().setNewPerPlaceDetail(ratio(created, r.getPlaceDetail()));
    }

    static Double ratio(int a, int b) {
        return b == 0 ? null : Math.round(a * 1000.0 / b) / 1000.0;
    }

    private static SnapshotRunReport finish(SnapshotRunReport report, SnapshotReportStore store,
                                            StopReason stop, String detail) {
        return finish(report, store, stop, detail, null);
    }

    /** {@code existing}: the IN_PROGRESS marker this run wrote, replaced by the final report. */
    private static SnapshotRunReport finish(SnapshotRunReport report, SnapshotReportStore store,
                                            StopReason stop, String detail, Path existing) {
        report.setStopReason(stop);
        report.setStopDetail(detail);
        report.setFinishedAt(Instant.now().toString());
        Path file = existing != null ? store.rewrite(existing, report) : store.write(report);
        log.info("Snapshot run {} ({}): {} — requests {}/{} (autocomplete {}, place detail {}), new {}, report {}",
            report.getMode(), report.getDiscovery(), stop, report.getRequests().getUsed(), report.getBudget(),
            report.getRequests().getAutocomplete(), report.getRequests().getPlaceDetail(),
            report.getNewRestaurants().size(), file.toAbsolutePath());
        return report;
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private static final class UnitState {
        final SnapshotPlan.Cell cell;
        final List<String> keywordsPending;
        final Set<String> placeIds = new LinkedHashSet<>();

        UnitState(SnapshotPlan.Cell cell) {
            this.cell = cell;
            this.keywordsPending = new ArrayList<>(cell.keywords());
        }
    }

    /** A resolved place_id: a stored restaurant (with coordinates), or nothing to store. */
    private record Resolved(Double lat, Double lng, boolean restaurant) {
        static final Resolved NOT_A_RESTAURANT = new Resolved(null, null, false);

        static Resolved at(Double lat, Double lng) {
            return new Resolved(lat, lng, true);
        }

        static Resolved of(Restaurant r) {
            return new Resolved(r.getLatitude(), r.getLongitude(), true);
        }

        boolean isLocalTo(double lat0, double lng0, CellCoverageService coverage) {
            return restaurant && coverage.isWithinCrawlRadius(lat0, lng0, lat, lng);
        }
    }

    /** Stored restaurants by place_id and by exact normalised name + address (no fuzzy matching). */
    static final class Known {
        final Map<String, KnownPlaceRow> byPlaceId = new HashMap<>();
        final Map<String, KnownPlaceRow> byNameAddress = new HashMap<>();

        /** Stored restaurants' points and normalised names (and those saved earlier in this run), for quality flags. */
        final List<double[]> points = new ArrayList<>();
        final Set<String> names = new java.util.HashSet<>();

        static Known load(List<KnownPlaceRow> rows) {
            Known k = new Known();
            for (KnownPlaceRow row : rows) {
                if (row.getGoongPlaceId() != null) k.byPlaceId.put(row.getGoongPlaceId(), row);
                if (StringUtils.hasText(row.getName()) && StringUtils.hasText(row.getAddress())) {
                    k.byNameAddress.putIfAbsent(key(row.getName(), row.getAddress()), row);
                }
                k.remember(row.getName(), row.getLatitude(), row.getLongitude());
            }
            return k;
        }

        /** A restaurant saved by this run: near / same-name checks see it, and later lookups find it by id or text. */
        void add(Restaurant r) {
            remember(r.getName(), r.getLatitude(), r.getLongitude());
            index(r);
        }

        /** Known by place_id (and exact name + address) from now on — a later wave resolves it without Goong. */
        void index(Restaurant r) {
            if (r == null) return;
            KnownPlaceRow row = new SavedRow(r.getId(), r.getGoongPlaceId(), r.getName(), r.getAddress(),
                r.getLatitude(), r.getLongitude());
            if (r.getGoongPlaceId() != null) byPlaceId.put(r.getGoongPlaceId(), row);
            if (StringUtils.hasText(r.getName()) && StringUtils.hasText(r.getAddress())) {
                byNameAddress.putIfAbsent(key(r.getName(), r.getAddress()), row);
            }
        }

        private record SavedRow(Long id, String goongPlaceId, String name, String address, Double latitude,
                                Double longitude) implements KnownPlaceRow {
            @Override public Long getId() { return id; }
            @Override public String getGoongPlaceId() { return goongPlaceId; }
            @Override public String getName() { return name; }
            @Override public String getAddress() { return address; }
            @Override public Double getLatitude() { return latitude; }
            @Override public Double getLongitude() { return longitude; }
        }

        private void remember(String name, Double lat, Double lng) {
            if (lat != null && lng != null) points.add(new double[]{lat, lng});
            if (StringUtils.hasText(name)) names.add(VietnameseText.normalize(name));
        }

        /**
         * Report-only data-quality flags for a newly saved restaurant (checked
         * before it is remembered): never merged, never moved.
         *   ADDRESS_WARD_ONLY        the address names no street ("…, Phường 11, Gò Vấp, …")
         *   ADDRESS_NO_HOUSE_NUMBER  a street but no house number
         *   NEAR_EXISTING_25M        another stored restaurant within 25 m
         *   SAME_NAME_AS_KNOWN       a stored restaurant has the same name (a branch, or a duplicate to review)
         */
        List<String> qualityFlags(Restaurant r) {
            List<String> flags = new ArrayList<>();
            String street = firstAddressPart(r.getName(), r.getAddress());
            if (street != null) {
                if (WARD.matcher(street).find()) flags.add("ADDRESS_WARD_ONLY");
                else if (!Character.isDigit(street.charAt(0))) flags.add("ADDRESS_NO_HOUSE_NUMBER");
            }
            if (r.getLatitude() != null && r.getLongitude() != null && points.stream()
                    .anyMatch(p -> GeoUtils.distanceMeters(p[0], p[1], r.getLatitude(), r.getLongitude()) <= NEAR_METERS)) {
                flags.add("NEAR_EXISTING_25M");
            }
            if (StringUtils.hasText(r.getName()) && names.contains(VietnameseText.normalize(r.getName()))) {
                flags.add("SAME_NAME_AS_KNOWN");
            }
            return flags;
        }

        private static final java.util.regex.Pattern WARD =
            java.util.regex.Pattern.compile("^(phường|p\\.|xã|thị trấn)(\\s|\\d)", java.util.regex.Pattern.CASE_INSENSITIVE | java.util.regex.Pattern.UNICODE_CASE);

        /** The address part after the place's own name (Goong addresses usually start with it), trimmed. */
        static String firstAddressPart(String name, String address) {
            if (!StringUtils.hasText(address)) return null;
            String rest = address.trim();
            if (StringUtils.hasText(name)) {
                String n = name.trim();   // the name itself may contain commas
                if (rest.length() > n.length() && rest.regionMatches(true, 0, n, 0, n.length())
                        && rest.charAt(n.length()) == ',') {
                    rest = rest.substring(n.length() + 1);
                }
            }
            String part = rest.split(",")[0].trim();
            return part.isEmpty() ? null : part;
        }

        /**
         * main_text = name and secondary_text = address, or (as Goong's
         * formatted addresses often start with the name) the stored address
         * equals "main_text, secondary_text".
         */
        Optional<KnownPlaceRow> byText(SnapshotPlan.PendingPlace p) {
            if (!StringUtils.hasText(p.mainText()) || !StringUtils.hasText(p.secondaryText())) return Optional.empty();
            KnownPlaceRow hit = byNameAddress.get(key(p.mainText(), p.secondaryText()));
            if (hit == null) hit = byNameAddress.get(key(p.mainText(), p.mainText() + ", " + p.secondaryText()));
            return Optional.ofNullable(hit);
        }

        static String key(String name, String address) {
            return VietnameseText.normalize(name) + '\u0001' + VietnameseText.normalize(address);
        }
    }
}
