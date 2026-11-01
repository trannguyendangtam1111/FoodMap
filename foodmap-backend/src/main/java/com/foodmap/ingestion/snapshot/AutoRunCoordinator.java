package com.foodmap.ingestion.snapshot;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.foodmap.catalog.AreaPreset;
import com.foodmap.catalog.AreaPresets;
import com.foodmap.catalog.FoodEntry;
import com.foodmap.catalog.FoodVocabulary;
import com.foodmap.common.security.SecretRedactor;
import com.foodmap.config.properties.IngestionProperties;
import com.foodmap.integration.goong.GoongRequestBudget;
import com.foodmap.ingestion.snapshot.SnapshotRunReport.StopReason;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

/**
 * One AUTO snapshot run (app.snapshot.auto=true):
 *
 *   GUARDS → DATABASE IDENTITY → LOCK → LEDGER (effective budget) → HISTORY
 *   → COVERAGE → RANK → [dry run: plan preview, report]
 *   → IN_PROGRESS marker → arm the budget once
 *   → wave 0: pending pairs of earlier runs (no Autocomplete repeated)
 *   → wave: plan (one area) → Autocomplete → CHECKPOINT → Place Detail → save
 *           → re-measure coverage → DB check → CHECKPOINT → next wave
 *   → stop → final report (+ .md summary)
 *
 * Kept for the whole run: one lock, one armed budget (the effective budget,
 * never more than the daily ceiling allows), one known-place set, one
 * rejected-id set. Every Goong request goes through GoongClientImpl, i.e.
 * through GoongRequestBudget. Without app.snapshot.execute=true: a dry run,
 * zero Goong requests, nothing written but the report.
 */
@Slf4j
@Component
@Profile("snapshot-ingest")
@RequiredArgsConstructor
public class AutoRunCoordinator {

    static final String RESUME_AREA = "(resume)";
    static final int MAX_TARGET = 20;
    static final double MAX_MIN_SCORE = 10;

    private final SnapshotIngestionService service;
    private final GoongRequestBudget budget;
    private final SnapshotRunLock runLock;
    private final ObjectMapper objectMapper;
    private final AreaPresets areaPresets;
    private final FoodVocabulary vocabulary;
    private final CoverageReader coverageReader;
    private final SnapshotDbGuard dbGuard;
    private final IngestionProperties ingestionProperties;

    private final AutoProbePlanner planner = new AutoProbePlanner();

    /**
     * @param environmentProblems guard failures found by the caller (e.g. a web server running)
     * @return the report, already written to the report directory
     */
    public SnapshotRunReport run(SnapshotProperties p, AutoProperties a, List<String> environmentProblems) {
        SnapshotReportStore store = new SnapshotReportStore(objectMapper, Path.of(p.getReportDir()));
        SnapshotRunReport report = new SnapshotRunReport();
        report.setRunId(UUID.randomUUID().toString());
        report.setStartedAt(Instant.now().toString());
        report.setMode(p.isExecute() ? SnapshotRunReport.Mode.AUTO : SnapshotRunReport.Mode.DRY_RUN);
        report.setDiscovery(SnapshotRunReport.Discovery.FOOD_PROBES);
        report.setBudget(p.getBudget());
        report.setLarge(p.isLarge());
        AutoRunReport auto = new AutoRunReport();
        report.setAuto(auto);
        AutoRunReport.Config config = auto.getConfig();
        config.setConfiguredBudget(p.getBudget());
        config.setDailyCeiling(p.getDailyCeiling());
        config.setTarget(a.getTarget());
        config.setMinScore(a.getMinScore());
        config.setWaveSize(a.getWaveSize());
        config.setMaxProbes(a.getMaxProbes());
        config.setExcludeFoods(new ArrayList<>(a.getExcludeFoods()));
        config.setAreas(new ArrayList<>(a.getAreas()));
        config.setPriorProbes(ProbeHistory.PRIOR_PROBES);

        List<String> problems = new ArrayList<>(environmentProblems);
        problems.addAll(guardProblems(p, a, ingestionProperties, areaPresets, vocabulary));
        if (!problems.isEmpty()) return finish(report, store, StopReason.REFUSED, String.join("; ", problems), null);

        // DATABASE IDENTITY — before the lock, before any Goong request.
        String database;
        try {
            database = dbGuard.identity();
        } catch (RuntimeException e) {
            return finish(report, store, StopReason.DB_SAFETY, "cannot read the database identity: " + e.getMessage(), null);
        }
        config.setDatabase(database);
        String allowed = SnapshotDbGuard.normalise(a.getAllowedDb());
        if (!database.equals(allowed)) {
            return finish(report, store, StopReason.DB_SAFETY, "database " + database
                + " is not the allowed one " + allowed + " (app.snapshot.auto.allowed-db)", null);
        }

        Optional<SnapshotRunLock.Handle> lock = runLock.tryAcquire();
        if (lock.isEmpty()) return finish(report, store, StopReason.LOCKED, "another snapshot run holds the lock", null);
        try (SnapshotRunLock.Handle ignored = lock.get()) {
            return runLocked(p, a, store, report);
        }
    }

    private SnapshotRunReport runLocked(SnapshotProperties p, AutoProperties a, SnapshotReportStore store,
                                        SnapshotRunReport report) {
        AutoRunReport auto = report.getAuto();
        AutoRunReport.Config config = auto.getConfig();
        Path dir = Path.of(p.getReportDir());

        // LEDGER — authoritative for usage, completed and pending pairs. Fails closed.
        SnapshotLedger.State state;
        try {
            state = new SnapshotLedger(objectMapper, dir).read(Instant.now());
        } catch (UncheckedIOException e) {
            state = new SnapshotLedger.State(0, 0, Set.of(), Map.of(), List.of(p.getReportDir() + " (" + e.getMessage() + ")"));
        }
        int effective = AutoBudget.effective(p.getBudget(), p.getDailyCeiling(), state.usageInWindow());
        int margin = AutoBudget.retryMargin(effective);
        config.setEffectiveBudget(effective);
        config.setRetryMargin(margin);

        SnapshotRunReport.Ledger ledger = new SnapshotRunReport.Ledger();
        ledger.setReportsRead(state.reportsRead());
        ledger.setPrevious24hUsage(state.usageInWindow());
        ledger.setRequestedBudget(p.getBudget());
        ledger.setProjected24hUsage(state.usageInWindow() + effective);
        ledger.setDailyCeiling(p.getDailyCeiling());
        ledger.setCompletedPairs(new ArrayList<>(new TreeSet<>(state.completedPairs())));
        ledger.setPendingPairs(new ArrayList<>(state.pendingPairs().keySet()));
        ledger.setUnreadableReports(new ArrayList<>(state.unreadable()));
        List<String> ledgerProblems = new ArrayList<>();
        if (!state.unreadable().isEmpty()) {
            ledgerProblems.add("cannot read snapshot report(s) " + String.join(", ", state.unreadable())
                + " — execution fails closed (Goong usage could be undercounted)");
        }
        boolean full = !AutoBudget.fitsOneProbe(effective);
        if (full) {
            ledgerProblems.add("LEDGER_FULL: " + effective + " request(s) left under the daily ceiling (" + state.usageInWindow()
                + " of " + p.getDailyCeiling() + " used in the last 24 h) — less than one probe ("
                + AutoBudget.WORST_CASE_PER_PROBE + ") + retry margin (" + margin + ")");
        }
        ledger.setProblems(ledgerProblems);
        ledger.setAllowed(ledgerProblems.isEmpty());
        report.setLedger(ledger);
        auto.setLedgerBefore(new AutoRunReport.LedgerView(state.usageInWindow(), state.completedPairs().size(),
            new ArrayList<>(state.pendingPairs().keySet())));
        auto.setProjectedLedgerUsage(state.usageInWindow() + effective);

        // HISTORY + PENDING WORK from the report files; COVERAGE from the database (read-only).
        List<SnapshotRunReport> archive = readArchive(dir);
        ProbeHistory history = ProbeHistory.of(archive);
        config.setGlobalUsefulPerProbe(AutoProbePlanner.round(history.globalUsefulPerProbe()));
        config.setGlobalPlaceDetailPerProbe(AutoProbePlanner.round(history.globalPlaceDetailPerProbe()));
        Map<String, SnapshotRunReport.PartialCell> pendingWork = pendingWork(archive, state.pendingPairs().keySet());

        List<AreaPreset> areas = areaPresets.all();
        List<AutoProbePlanner.Food> foods = vocabulary.discoverable().stream()
            .map(e -> new AutoProbePlanner.Food(e.key(), e.kind().name(), e.order() == null ? Integer.MAX_VALUE : e.order()))
            .toList();
        List<String> foodKeys = foods.stream().map(AutoProbePlanner.Food::key).toList();
        foodKeys.forEach(f -> auto.getHistory().put(f, foodHistory(history, f)));
        Map<String, Map<String, Integer>> coverageBefore = coverageReader.read(areas, foodKeys);
        auto.setCoverageBefore(coverageBefore);

        Ctx ctx = new Ctx(p, a, store, report, areas, foods, state, history, margin, effective);
        ctx.coverage = coverageBefore;
        AutoProbePlanner.Ranking ranking = planner.rank(ctx.input());
        ranking.eligible().forEach(s -> auto.getCandidates().add(s.candidate()));
        auto.getSkipped().addAll(ranking.skipped());

        if (!p.isExecute()) {
            preview(ctx, pendingWork);
            String verdict = ledger.isAllowed() ? "would be allowed (effective budget " + effective + ")"
                : (full && state.unreadable().isEmpty() ? "would stop with " : "would be refused: ") + String.join("; ", ledgerProblems);
            return finish(report, store, StopReason.DRY_RUN,
                "plan only — add --app.snapshot.execute=true to run it; execution " + verdict, null);
        }
        if (!state.unreadable().isEmpty()) return finish(report, store, StopReason.REFUSED, String.join("; ", ledgerProblems), null);
        if (full) return finish(report, store, StopReason.LEDGER_FULL, String.join("; ", ledgerProblems), null);

        // EXECUTE — the marker (with the effective budget: a run that dies counts all of it) goes to disk first.
        report.setBudget(effective);
        report.setStopReason(StopReason.IN_PROGRESS);
        ctx.file = store.write(report);
        StopReason stop = null;
        String detail = null;
        try {
            ctx.baseline = dbGuard.baseline();
            ctx.rejected = new LinkedHashSet<>(store.loadRejectedIds());
            budget.arm(effective);
            ctx.known = service.loadKnown();

            if (!pendingWork.isEmpty()) {
                int cost = pendingWork.values().stream()
                    .mapToInt(pc -> AutoBudget.worstCaseResume(pc.getKeywordsPending().size(), pc.getPendingPlaces().size()))
                    .sum();
                if (cost <= effective - margin) {
                    StopReason ws = runResumeWave(ctx, pendingWork);
                    if (isStop(ws)) {
                        stop = ws;
                        detail = ctx.lastDetail;
                    }
                } else {
                    auto.getSkipped().add(new AutoRunReport.Skipped(String.join(", ", pendingWork.keySet()),
                        "pending work needs up to " + cost + " request(s) — left for a run with more budget"));
                }
            }

            String previousArea = null;
            while (stop == null) {
                if (ctx.probes >= a.getMaxProbes()) {
                    stop = StopReason.MAX_PROBES_REACHED;
                    detail = ctx.probes + " probe(s) ran (app.snapshot.auto.max-probes=" + a.getMaxProbes() + ")";
                    break;
                }
                int remaining = effective - budget.used();
                int limit = Math.min(Math.min(a.getWaveSize(), a.getMaxProbes() - ctx.probes),
                    AutoBudget.probesThatFit(remaining, margin));
                if (limit < 1) {
                    stop = StopReason.COMPLETED;
                    detail = "remaining budget " + remaining + " is below one probe (" + AutoBudget.WORST_CASE_PER_PROBE
                        + ") + retry margin (" + margin + ")";
                    break;
                }
                Optional<AutoProbePlanner.Wave> wave = planner.nextWave(planner.rank(ctx.input()), areas, previousArea, limit);
                if (wave.isEmpty()) {
                    stop = StopReason.NO_ELIGIBLE_WORK;
                    detail = "no food × area pair is eligible with a score ≥ " + a.getMinScore();
                    break;
                }
                StopReason ws = runProbeWave(ctx, wave.get());
                previousArea = wave.get().area();
                if (isStop(ws)) {
                    stop = ws;
                    detail = ctx.lastDetail;
                }
            }
        } catch (RuntimeException e) {
            RuntimeException safe = SecretRedactor.sanitize(e);
            log.error("AUTO snapshot run failed unexpectedly", safe);
            stop = StopReason.ERROR;
            detail = safe.getMessage();
        } finally {
            SnapshotIngestionService.copyRequests(report, budget.snapshot());
            budget.disarm();
        }

        summarise(ctx, coverageBefore);
        return finish(report, store, stop, detail, ctx.file);
    }

    // ── Waves ────────────────────────────────────────────────────────────────

    private StopReason runProbeWave(Ctx ctx, AutoProbePlanner.Wave wave) {
        AreaPreset area = areaPresets.get(wave.area()).orElseThrow();
        List<SnapshotPlan.Cell> cells = new ArrayList<>();
        AutoRunReport.Wave meta = new AutoRunReport.Wave();
        meta.setIndex(ctx.report.getAuto().getWaves().size());
        meta.setArea(area.key());
        meta.setReason(wave.reason());
        for (AutoProbePlanner.Scored s : wave.probes()) {
            String food = s.candidate().getFood();
            FoodEntry entry = vocabulary.get(food).orElseThrow();
            cells.add(new SnapshotPlan.Cell(s.pair(), area.centerLat(), area.centerLng(), SnapshotPlan.Priority.FOOD_PROBE,
                List.of(entry.label()), List.of(), 0, area.radiusMeters(), food, area.key()));
            AutoRunReport.WaveProbe probe = new AutoRunReport.WaveProbe();
            probe.setPlanned(s.candidate());
            meta.getProbes().add(probe);
        }
        SnapshotPlan plan = new SnapshotPlan(cells, List.of(circle(area)), cells.size(), List.of());
        ctx.probes += cells.size();
        return runWave(ctx, plan, meta);
    }

    private StopReason runResumeWave(Ctx ctx, Map<String, SnapshotRunReport.PartialCell> pending) {
        List<SnapshotPlan.Cell> cells = new ArrayList<>();
        List<SnapshotPlan.Circle> region = new ArrayList<>();
        AutoRunReport.Wave meta = new AutoRunReport.Wave();
        meta.setIndex(0);
        meta.setArea(RESUME_AREA);
        meta.setReason("pending work of earlier runs: " + pending.size() + " pair(s), no Autocomplete repeated");
        for (SnapshotRunReport.PartialCell pc : pending.values()) {
            cells.add(new SnapshotPlan.Cell(pc.getCellKey(), pc.getLat(), pc.getLng(), SnapshotPlan.Priority.RESUMED,
                List.copyOf(pc.getKeywordsPending()), List.copyOf(pc.getPendingPlaces()), pc.getPlacesLocal(),
                pc.getRadiusMeters(), pc.getFoodKey(), pc.getAreaKey()));
            SnapshotPlan.Circle c = pc.getAreaKey() == null ? null : areaPresets.get(pc.getAreaKey()).map(AutoRunCoordinator::circle).orElse(null);
            if (c == null) c = new SnapshotPlan.Circle(pc.getLat(), pc.getLng(), pc.getRadiusMeters() + AutoProbePlanner.SAVE_MARGIN_METERS);
            if (!region.contains(c)) region.add(c);
            AutoRunReport.Candidate planned = new AutoRunReport.Candidate();
            planned.setPair(pc.getCellKey());
            planned.setArea(pc.getAreaKey());
            planned.setFood(pc.getFoodKey());
            AutoRunReport.WaveProbe probe = new AutoRunReport.WaveProbe();
            probe.setPlanned(planned);
            meta.getProbes().add(probe);
            ctx.report.getAuto().getResumedPairs().add(pc.getCellKey());
        }
        int discovery = cells.stream().mapToInt(c -> c.keywords().size()).sum();
        return runWave(ctx, new SnapshotPlan(cells, region, discovery, List.of()), meta);
    }

    /** Executes one wave, merges it into the run report, re-measures coverage, checks the database, checkpoints. */
    private StopReason runWave(Ctx ctx, SnapshotPlan plan, AutoRunReport.Wave meta) {
        SnapshotRunReport wave = new SnapshotRunReport();
        int before = budget.used();
        StopReason ws = service.executeWave(plan, wave, ctx.rejected, ctx.known,
            pending -> checkpoint(ctx, pending));
        merge(ctx.report, wave);
        wave.getRejected().forEach(r -> ctx.rejected.add(r.getPlaceId()));
        plan.cells().forEach(c -> ctx.probedThisRun.add(c.cellKey()));
        ctx.created += wave.getNewRestaurants().size();
        ctx.rekeyed += wave.getUpdatedIds().size();
        meta.setRequestsUsed(budget.used() - before);
        meta.setStopReason(ws);
        for (AutoRunReport.WaveProbe probe : meta.getProbes()) fillResult(probe, wave);

        ctx.coverage = coverageReader.read(ctx.areas, ctx.foodKeys());
        Map<String, Integer> after = new LinkedHashMap<>();
        for (SnapshotPlan.Cell c : plan.cells()) {
            after.put(c.foodKey() + "@" + c.areaKey(), ctx.coverage.getOrDefault(c.areaKey(), Map.of()).getOrDefault(c.foodKey(), 0));
        }
        meta.setCoverageAfter(after);

        List<String> problems = dbGuard.verify(ctx.baseline, ctx.created, ctx.rekeyed);
        meta.setDbCheck(problems.isEmpty() ? "OK" : String.join("; ", problems));
        ctx.report.getAuto().getWaves().add(meta);
        checkpoint(ctx, List.of());
        if (!problems.isEmpty()) {
            ctx.lastDetail = "database check after wave " + meta.getIndex() + ": " + String.join("; ", problems);
            return StopReason.DB_SAFETY;
        }
        ctx.lastDetail = wave.getStopDetail();
        return ws;
    }

    /** A wave result that ends the run (anything else: continue; pending pairs are resumed next run). */
    private static boolean isStop(StopReason ws) {
        return ws == StopReason.BUDGET_EXHAUSTED || ws == StopReason.RATE_LIMITED
            || ws == StopReason.PROVIDER_DEGRADED || ws == StopReason.DB_SAFETY || ws == StopReason.ERROR;
    }

    /** Rewrites the IN_PROGRESS report with everything so far (+ the current wave's unresolved discoveries). */
    private void checkpoint(Ctx ctx, List<SnapshotRunReport.PartialCell> inFlight) {
        SnapshotRunReport r = ctx.report;
        List<SnapshotRunReport.PartialCell> saved = r.getPartialCells();
        List<SnapshotRunReport.PartialCell> withFlight = new ArrayList<>(saved);
        withFlight.addAll(inFlight);
        r.setPartialCells(withFlight);
        SnapshotIngestionService.copyRequests(r, budget.snapshot());
        try {
            ctx.store.rewrite(ctx.file, r);
        } finally {
            r.setPartialCells(saved);
        }
    }

    private static void fillResult(AutoRunReport.WaveProbe probe, SnapshotRunReport wave) {
        String unit = probe.getPlanned().getPair();
        for (SnapshotRunReport.QueryResult q : wave.getQueries()) {
            if (!unit.equals(q.getUnitKey())) continue;
            probe.setAutocomplete(probe.getAutocomplete() + q.getAutocompleteRequests());
            probe.setPlaceDetail(probe.getPlaceDetail() + q.getPlaceDetailRequests());
            probe.setRequests(probe.getRequests() + q.getAutocompleteRequests() + q.getPlaceDetailRequests());
            probe.setPredictions(probe.getPredictions() + q.getPredictions());
            probe.setNewRestaurants(probe.getNewRestaurants() + q.getNewRestaurants());
            probe.setRelevant(probe.getRelevant() + q.getFoodRelevantNew());
            probe.setRejected(probe.getRejected() + q.getRejected());
            probe.setDuplicates(probe.getDuplicates() + q.getDuplicates() + q.getAlreadySeenInRun());
            probe.setResolvedWithoutDetail(probe.getResolvedWithoutDetail() + q.getKnownById() + q.getKnownByText()
                + q.getPreviouslyRejected());
            probe.setPending(probe.getPending() + q.getPending());
        }
        probe.setInsideRelevant((int) wave.getNewRestaurants().stream()
            .filter(n -> unit.equals(n.getUnitKey()) && SnapshotIngestionService.RELEVANCE_MATCH.equals(n.getRelevance())
                && Boolean.TRUE.equals(n.getInsideRuntimeRadius()))
            .count());
    }

    /** Adds one wave's outcome to the run report. */
    static void merge(SnapshotRunReport run, SnapshotRunReport wave) {
        run.setPredictions(run.getPredictions() + wave.getPredictions());
        run.setDistinctIds(run.getDistinctIds() + wave.getDistinctIds());
        run.setDuplicates(run.getDuplicates() + wave.getDuplicates());
        run.setKnownById(run.getKnownById() + wave.getKnownById());
        run.setKnownByText(run.getKnownByText() + wave.getKnownByText());
        run.setPreviouslyRejected(run.getPreviouslyRejected() + wave.getPreviouslyRejected());
        run.setUnknownIds(run.getUnknownIds() + wave.getUnknownIds());
        run.getNewRestaurants().addAll(wave.getNewRestaurants());
        run.getUpdatedIds().addAll(wave.getUpdatedIds());
        run.getRejected().addAll(wave.getRejected());
        run.getPlannedCells().addAll(wave.getPlannedCells());
        run.getCompletedProbes().addAll(wave.getCompletedProbes());
        run.getPartialCells().addAll(wave.getPartialCells());
        run.getPendingIds().addAll(wave.getPendingIds());
        run.getQueries().addAll(wave.getQueries());
    }

    // ── Dry run ──────────────────────────────────────────────────────────────

    /**
     * The waves the run would start with, from the current coverage: chosen
     * pairs count as probed, coverage is not guessed (an executing run
     * re-measures it after every wave, so its later waves can differ).
     */
    private void preview(Ctx ctx, Map<String, SnapshotRunReport.PartialCell> pendingWork) {
        AutoRunReport auto = ctx.report.getAuto();
        int remaining = ctx.effective;
        int maxRequests = 0;
        double expected = 0;
        if (!pendingWork.isEmpty()) {
            AutoRunReport.Wave resume = new AutoRunReport.Wave();
            resume.setIndex(0);
            resume.setArea(RESUME_AREA);
            resume.setReason("pending work of earlier runs: " + String.join(", ", pendingWork.keySet()));
            for (SnapshotRunReport.PartialCell pc : pendingWork.values()) {
                int cost = AutoBudget.worstCaseResume(pc.getKeywordsPending().size(), pc.getPendingPlaces().size());
                maxRequests += cost;
                expected += cost;
                AutoRunReport.Candidate planned = new AutoRunReport.Candidate();
                planned.setPair(pc.getCellKey());
                planned.setArea(pc.getAreaKey());
                planned.setFood(pc.getFoodKey());
                AutoRunReport.WaveProbe probe = new AutoRunReport.WaveProbe();
                probe.setPlanned(planned);
                resume.getProbes().add(probe);
                ctx.probedThisRun.add(pc.getCellKey());
            }
            remaining -= maxRequests;
            auto.getPlannedWaves().add(resume);
        }
        String previousArea = null;
        int probes = 0;
        while (true) {
            int limit = Math.min(Math.min(ctx.a.getWaveSize(), ctx.a.getMaxProbes() - probes),
                AutoBudget.probesThatFit(remaining, ctx.margin));
            if (limit < 1) break;
            Optional<AutoProbePlanner.Wave> wave = planner.nextWave(planner.rank(ctx.input()), ctx.areas, previousArea, limit);
            if (wave.isEmpty()) break;
            AutoRunReport.Wave meta = new AutoRunReport.Wave();
            meta.setIndex(auto.getPlannedWaves().size());
            meta.setArea(wave.get().area());
            meta.setReason(wave.get().reason());
            for (AutoProbePlanner.Scored s : wave.get().probes()) {
                AutoRunReport.WaveProbe probe = new AutoRunReport.WaveProbe();
                probe.setPlanned(s.candidate());
                meta.getProbes().add(probe);
                ctx.probedThisRun.add(s.pair());
                expected += s.candidate().getExpectedCost();
            }
            int k = wave.get().probes().size();
            probes += k;
            maxRequests += k * AutoBudget.WORST_CASE_PER_PROBE;
            remaining -= k * AutoBudget.WORST_CASE_PER_PROBE;
            previousArea = wave.get().area();
            auto.getPlannedWaves().add(meta);
        }
        auto.setEstimatedMaxRequests(maxRequests);
        auto.setEstimatedExpectedRequests(AutoProbePlanner.round(expected));
        ctx.probedThisRun.clear();
    }

    // ── Summary ──────────────────────────────────────────────────────────────

    private void summarise(Ctx ctx, Map<String, Map<String, Integer>> before) {
        AutoRunReport auto = ctx.report.getAuto();
        Map<String, Map<String, Integer>> after = ctx.coverage;
        auto.setCoverageAfter(after);
        int target = ctx.a.getTarget();
        for (AreaPreset area : ctx.areas) {
            for (String food : ctx.foodKeys()) {
                int b = before.getOrDefault(area.key(), Map.of()).getOrDefault(food, 0);
                int n = after.getOrDefault(area.key(), Map.of()).getOrDefault(food, 0);
                if (n > b && !ctx.probedThisRun.contains(SnapshotPlan.foodProbeKey(food, area.key()))) {
                    auto.getSpillOver().add(new AutoRunReport.CoverageChange(area.key(), food, b, n));
                }
                if (b < target && n >= target) {
                    auto.getPairsReachedTarget().add(new AutoRunReport.CoverageChange(area.key(), food, b, n));
                }
            }
        }
        Set<String> completed = new LinkedHashSet<>(ctx.state.completedPairs());
        completed.addAll(ctx.report.getCompletedProbes());
        Set<String> pending = new LinkedHashSet<>(ctx.state.pendingPairs().keySet());
        pending.removeAll(completed);
        ctx.report.getPartialCells().stream().filter(pc -> pc.getFoodKey() != null).forEach(pc -> pending.add(pc.getCellKey()));
        auto.setLedgerAfter(new AutoRunReport.LedgerView(ctx.state.usageInWindow() + ctx.report.getRequests().getUsed(),
            completed.size(), new ArrayList<>(pending)));
    }

    private SnapshotRunReport finish(SnapshotRunReport report, SnapshotReportStore store, StopReason stop,
                                     String detail, Path existing) {
        report.setStopReason(stop);
        report.setStopDetail(SecretRedactor.redact(detail));
        report.setFinishedAt(Instant.now().toString());
        Path file = existing != null ? store.rewrite(existing, report) : store.write(report);
        String md = file.getFileName().toString().replaceFirst("\\.json$", "") + ".md";
        report.getAuto().setSummaryFile(md);
        store.rewrite(file, report);
        store.writeSummary(file, AutoReportMarkdown.render(report));
        AutoRunReport.Config c = report.getAuto().getConfig();
        log.info("AUTO snapshot run {}: {} — requests {}/{} (autocomplete {}, place detail {}), waves {}, new {}, report {}",
            report.getMode(), stop, report.getRequests().getUsed(), c.getEffectiveBudget(),
            report.getRequests().getAutocomplete(), report.getRequests().getPlaceDetail(),
            report.getAuto().getWaves().size(), report.getNewRestaurants().size(), file.toAbsolutePath());
        return report;
    }

    // ── Inputs ───────────────────────────────────────────────────────────────

    static List<String> guardProblems(SnapshotProperties p, AutoProperties a, IngestionProperties ingestion,
                                      AreaPresets areaPresets, FoodVocabulary vocabulary) {
        List<String> problems = new ArrayList<>();
        Integer b = p.getBudget();
        if (b == null) {
            problems.add("app.snapshot.budget is required");
        } else if (b < 1 || b > GoongRequestBudget.MAX_LIMIT) {
            problems.add("app.snapshot.budget must be 1.." + GoongRequestBudget.MAX_LIMIT + " (was " + b + ")");
        } else if (b > SnapshotProperties.LARGE_BUDGET && !p.isLarge()) {
            problems.add("a budget above " + SnapshotProperties.LARGE_BUDGET + " needs app.snapshot.large=true");
        }
        if (p.getDailyCeiling() < 1 || p.getDailyCeiling() > SnapshotProperties.MAX_DAILY_CEILING) {
            problems.add("app.snapshot.daily-ceiling must be 1.." + SnapshotProperties.MAX_DAILY_CEILING + " (was " + p.getDailyCeiling() + ")");
        }
        if (ingestion.isSeedOnStartup() || ingestion.isViewportEnqueueEnabled() || ingestion.isScheduledRecrawlEnabled()) {
            problems.add("automatic ingestion triggers must be off (seed-on-startup, viewport-enqueue-enabled, scheduled-recrawl-enabled)");
        }
        if (!p.getAreas().isEmpty() || !p.getCells().isEmpty() || !p.getFoods().isEmpty() || p.getResume() != null) {
            problems.add("app.snapshot.auto cannot be combined with areas, cells, foods or resume (use app.snapshot.auto.areas)");
        }
        if (a.getTarget() < 1 || a.getTarget() > MAX_TARGET) {
            problems.add("app.snapshot.auto.target must be 1.." + MAX_TARGET + " (was " + a.getTarget() + ")");
        }
        if (!(a.getMinScore() >= 0 && a.getMinScore() <= MAX_MIN_SCORE)) {
            problems.add("app.snapshot.auto.min-score must be 0.." + MAX_MIN_SCORE + " (was " + a.getMinScore() + ")");
        }
        if (a.getWaveSize() < 1 || a.getWaveSize() > AutoProperties.MAX_WAVE_SIZE) {
            problems.add("app.snapshot.auto.wave-size must be 1.." + AutoProperties.MAX_WAVE_SIZE + " (was " + a.getWaveSize() + ")");
        }
        if (a.getMaxProbes() < 1 || a.getMaxProbes() > AutoProperties.MAX_PROBES_LIMIT) {
            problems.add("app.snapshot.auto.max-probes must be 1.." + AutoProperties.MAX_PROBES_LIMIT + " (was " + a.getMaxProbes() + ")");
        }
        if (a.getAllowedDb() == null || a.getAllowedDb().isBlank()) {
            problems.add("app.snapshot.auto.allowed-db is required");
        }
        for (String area : a.getAreas()) {
            if (areaPresets.get(area.trim()).isEmpty()) problems.add("unknown area in app.snapshot.auto.areas: " + area);
        }
        for (String food : a.getExcludeFoods()) {
            if (vocabulary.get(food.trim()).isEmpty()) problems.add("unknown food in app.snapshot.auto.exclude-foods: " + food);
        }
        return problems;
    }

    /** Every readable report in name order (unreadable ones are the ledger's to refuse on). */
    private List<SnapshotRunReport> readArchive(Path dir) {
        List<Path> files = new ArrayList<>();
        if (Files.isDirectory(dir)) {
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir, "*.json")) {
                stream.forEach(files::add);
            } catch (IOException e) {
                throw new UncheckedIOException("Could not list snapshot reports in " + dir, e);
            }
        }
        files.sort(null);
        List<SnapshotRunReport> out = new ArrayList<>();
        for (Path f : files) {
            try {
                out.add(objectMapper.readValue(f.toFile(), SnapshotRunReport.class));
            } catch (IOException | RuntimeException e) {
                log.debug("AUTO history skips unreadable report {}", f.getFileName());
            }
        }
        return out;
    }

    /** The latest partial state of each pending food × area pair the ledger lists. */
    static Map<String, SnapshotRunReport.PartialCell> pendingWork(List<SnapshotRunReport> archive, Set<String> pendingPairs) {
        Map<String, SnapshotRunReport.PartialCell> latest = new LinkedHashMap<>();
        for (SnapshotRunReport r : archive) {
            for (SnapshotRunReport.PartialCell pc : r.getPartialCells()) {
                if (pc.getFoodKey() != null) latest.put(pc.getCellKey(), pc);
            }
            r.getCompletedProbes().forEach(latest::remove);
        }
        latest.keySet().retainAll(pendingPairs);
        return latest;
    }

    private static AutoRunReport.FoodHistory foodHistory(ProbeHistory history, String food) {
        ProbeHistory.FoodStats s = history.stats(food);
        AutoRunReport.FoodHistory h = new AutoRunReport.FoodHistory();
        h.setProbes(s.probes());
        h.setRequests(s.requests());
        h.setPlaceDetail(s.placeDetail());
        h.setRelevant(s.relevant());
        h.setInside(s.inside());
        h.setUsefulPerProbe(SnapshotIngestionService.ratio(s.inside(), s.probes()));
        h.setRelevantPerRequest(SnapshotIngestionService.ratio(s.relevant(), s.requests()));
        h.setInsidePerRequest(SnapshotIngestionService.ratio(s.inside(), s.requests()));
        h.setPlaceDetailPerProbe(SnapshotIngestionService.ratio(s.placeDetail(), s.probes()));
        h.setBlendedUseful(AutoProbePlanner.round(history.expectedUseful(food)));
        h.setBlendedCost(AutoProbePlanner.round(history.expectedCost(food)));
        return h;
    }

    static SnapshotPlan.Circle circle(AreaPreset area) {
        return new SnapshotPlan.Circle(area.centerLat(), area.centerLng(), area.radiusMeters() + AutoProbePlanner.SAVE_MARGIN_METERS);
    }

    /** The run's mutable state. */
    private static final class Ctx {
        final SnapshotProperties p;
        final AutoProperties a;
        final SnapshotReportStore store;
        final SnapshotRunReport report;
        final List<AreaPreset> areas;
        final List<AutoProbePlanner.Food> foods;
        final SnapshotLedger.State state;
        final ProbeHistory history;
        final int margin;
        final int effective;
        final Set<String> probedThisRun = new LinkedHashSet<>();
        Map<String, Map<String, Integer>> coverage;
        Path file;
        SnapshotDbGuard.Baseline baseline;
        Set<String> rejected;
        SnapshotIngestionService.Known known;
        int probes;
        int created;
        int rekeyed;
        String lastDetail;

        Ctx(SnapshotProperties p, AutoProperties a, SnapshotReportStore store, SnapshotRunReport report,
            List<AreaPreset> areas, List<AutoProbePlanner.Food> foods, SnapshotLedger.State state,
            ProbeHistory history, int margin, int effective) {
            this.p = p;
            this.a = a;
            this.store = store;
            this.report = report;
            this.areas = areas;
            this.foods = foods;
            this.state = state;
            this.history = history;
            this.margin = margin;
            this.effective = effective;
        }

        List<String> foodKeys() {
            return foods.stream().map(AutoProbePlanner.Food::key).toList();
        }

        AutoProbePlanner.Input input() {
            Set<String> allowed = new LinkedHashSet<>();
            a.getAreas().forEach(x -> allowed.add(x.trim()));
            Set<String> excluded = new LinkedHashSet<>();
            a.getExcludeFoods().forEach(x -> excluded.add(x.trim()));
            return new AutoProbePlanner.Input(areas, allowed, foods, excluded, coverage, state.completedPairs(),
                state.pendingPairs().keySet(), probedThisRun, history, a.getTarget(), a.getMinScore());
        }
    }
}
