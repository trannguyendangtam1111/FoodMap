package com.foodmap.ingestion.snapshot;

import com.foodmap.catalog.AreaPreset;
import com.foodmap.catalog.AreaPresets;
import com.foodmap.common.util.GeoUtils;
import com.foodmap.config.properties.IngestionProperties;
import com.foodmap.ingestion.CellCoverageService;
import com.foodmap.ingestion.domain.PlaceCell;
import com.foodmap.ingestion.repository.PlaceCellRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Chooses a snapshot run's cells — deterministically, from PostgreSQL only.
 *
 *   candidates  geohash-6 cells whose centre lies inside an area preset's
 *               radius, plus explicitly listed cells
 *   priority    1. never crawled  2. sparse or stale  3. crawled (only with
 *               includeCrawled); then explicit cells first (in the order
 *               given), then area cells nearest to the area centre, cell key
 *   size        at most maxCells, and at most what the discovery share
 *               (40%) of the budget can pay for — the rest of the budget is
 *               the Place Detail reserve
 *   region      area radius + 500 m; an explicit cell: 1 km around its centre
 *
 * Food probes (planFoodProbes) instead: one area preset, one Autocomplete per
 * requested food (its catalog label) at the area centre within the area
 * radius, in the order given — no cell fan-out, never trimmed or topped up.
 */
@Component
@Profile("snapshot-ingest")
@RequiredArgsConstructor
public class SnapshotPlanner {

    /** Share of the budget discovery (Autocomplete) may use; the rest is reserved for Place Detail. */
    static final double DISCOVERY_SHARE = 0.4;
    static final double AREA_MARGIN_METERS = 500;
    static final double EXPLICIT_CELL_REGION_METERS = 1_000;
    private static final double GRID_STEP_DEG = 0.003;   // < a geohash-6 cell side (as CellCoverageService)
    private static final Pattern CELL_KEY = Pattern.compile("^[0-9b-hjkmnp-z]{6}$");

    private final AreaPresets areaPresets;
    private final PlaceCellRepository placeCellRepository;
    private final CellCoverageService cellCoverageService;
    private final IngestionProperties ingestionProperties;
    private final SnapshotFoodQueries foodQueries;

    /** The Autocomplete inputs per cell — the existing discovery keywords, unchanged. */
    public List<String> keywords() {
        int cap = Math.max(0, ingestionProperties.getMaxKeywordsPerCell());
        return ingestionProperties.getKeywords().stream()
            .filter(StringUtils::hasText)
            .limit(cap)
            .toList();
    }

    public static int discoveryCap(int budget) {
        return (int) Math.floor(budget * DISCOVERY_SHARE);
    }

    /** A new run's plan. Throws IllegalArgumentException with a readable reason when it cannot plan. */
    public SnapshotPlan plan(SnapshotProperties p, int budget) {
        List<String> keywords = keywords();
        if (keywords.isEmpty()) throw new IllegalArgumentException("no discovery keywords are configured");

        Map<String, Candidate> candidates = new LinkedHashMap<>();
        List<SnapshotPlan.Circle> region = new ArrayList<>();

        for (int i = 0; i < p.getCells().size(); i++) {
            String key = p.getCells().get(i);
            String cell = key.trim();
            if (!CELL_KEY.matcher(cell).matches()) throw new IllegalArgumentException("not a geohash-6 cell key: " + key);
            double[] c = cellCoverageService.decodeCenter(cell);
            candidates.putIfAbsent(cell, new Candidate(cell, c[0], c[1], true, i, 0));
            region.add(new SnapshotPlan.Circle(c[0], c[1], EXPLICIT_CELL_REGION_METERS));
        }
        for (String key : p.getAreas()) {
            AreaPreset area = areaPresets.get(key.trim())
                .orElseThrow(() -> new IllegalArgumentException("unknown area: " + key));
            region.add(new SnapshotPlan.Circle(area.centerLat(), area.centerLng(), area.radiusMeters() + AREA_MARGIN_METERS));
            for (Candidate c : cellsInArea(area)) {
                candidates.merge(c.key(), c, (a, b) -> a.distance() <= b.distance() ? a : b);
            }
        }
        if (candidates.isEmpty()) throw new IllegalArgumentException("no cells to plan (give areas or cells)");

        Map<String, PlaceCell> crawled = placeCellRepository.findAllById(candidates.keySet()).stream()
            .collect(Collectors.toMap(PlaceCell::getCellKey, Function.identity()));

        List<String> skipped = new ArrayList<>();
        List<Ranked> eligible = new ArrayList<>();
        for (Candidate c : candidates.values()) {
            SnapshotPlan.Priority priority = priorityOf(crawled.get(c.key()));
            if (priority == SnapshotPlan.Priority.CRAWLED && !p.isIncludeCrawled()) {
                skipped.add(c.key() + ": already crawled (use include-crawled to allow)");
                continue;
            }
            eligible.add(new Ranked(c, priority));
        }
        eligible.sort(Comparator.comparing((Ranked r) -> r.priority().ordinal())
            .thenComparing(r -> !r.candidate().explicit())
            .thenComparingInt(r -> r.candidate().given())
            .thenComparingDouble(r -> r.candidate().distance())
            .thenComparing(r -> r.candidate().key()));

        int byBudget = discoveryCap(budget) / keywords.size();
        if (byBudget < 1) {
            throw new IllegalArgumentException("budget " + budget + " is too small: one cell needs "
                + keywords.size() + " Autocomplete call(s) within the discovery share ("
                + (int) (DISCOVERY_SHARE * 100) + "%) of the budget");
        }
        int take = Math.min(Math.min(p.getMaxCells(), byBudget), eligible.size());
        for (int i = take; i < eligible.size(); i++) {
            skipped.add(eligible.get(i).candidate().key() + ": beyond max-cells / budget");
        }

        List<SnapshotPlan.Cell> cells = eligible.subList(0, take).stream()
            .map(r -> SnapshotPlan.Cell.mapCell(r.candidate().key(), r.candidate().lat(), r.candidate().lng(),
                r.priority(), keywords, List.of(), 0))
            .toList();
        return new SnapshotPlan(cells, region, cells.size() * keywords.size(), skipped);
    }

    /** Resume a previous run: only its pending work — no new cells, no Autocomplete already done. */
    public SnapshotPlan planResume(SnapshotRunReport previous, int budget) {
        if (previous.getPartialCells().isEmpty()) {
            throw new IllegalArgumentException("the report has no pending work to resume");
        }
        if (previous.getRegion().isEmpty()) throw new IllegalArgumentException("the report has no target region");
        List<SnapshotPlan.Cell> cells = previous.getPartialCells().stream()
            .map(pc -> new SnapshotPlan.Cell(pc.getCellKey(), pc.getLat(), pc.getLng(), SnapshotPlan.Priority.RESUMED,
                List.copyOf(pc.getKeywordsPending()), List.copyOf(pc.getPendingPlaces()), pc.getPlacesLocal(),
                pc.getRadiusMeters() > 0 ? pc.getRadiusMeters() : CellCoverageService.CELL_RADIUS_METERS,
                pc.getFoodKey(), pc.getAreaKey()))
            .toList();
        int discovery = cells.stream().mapToInt(c -> c.keywords().size()).sum();
        if (discovery > discoveryCap(budget)) {
            throw new IllegalArgumentException("resuming needs " + discovery
                + " Autocomplete call(s), more than the discovery share of budget " + budget);
        }
        return new SnapshotPlan(cells, List.copyOf(previous.getRegion()), discovery, List.of());
    }

    /**
     * A food-probe plan: exactly one area preset; each requested food (in the
     * order given, duplicates dropped) becomes one Autocomplete of its catalog
     * label at the area centre, radius = the area radius. Exactly those
     * queries — refused (never trimmed) when they exceed the discovery share.
     */
    public SnapshotPlan planFoodProbes(SnapshotProperties p, int budget) {
        if (p.getAreas().size() != 1) throw new IllegalArgumentException("food probes need exactly one area");
        AreaPreset area = areaPresets.get(p.getAreas().get(0).trim())
            .orElseThrow(() -> new IllegalArgumentException("unknown area: " + p.getAreas().get(0)));
        List<SnapshotPlan.Cell> probes = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (String raw : p.getFoods()) {
            String key = raw.trim();
            if (!seen.add(key)) continue;
            SnapshotFoodQueries.FoodProbe probe = foodQueries.probeFor(key)
                .orElseThrow(() -> new IllegalArgumentException("not a discoverable catalog food: " + key));
            probes.add(new SnapshotPlan.Cell(SnapshotPlan.foodProbeKey(probe.foodKey(), area.key()),
                area.centerLat(), area.centerLng(), SnapshotPlan.Priority.FOOD_PROBE,
                List.of(probe.query()), List.of(), 0, area.radiusMeters(), probe.foodKey(), area.key()));
        }
        if (probes.isEmpty()) throw new IllegalArgumentException("no food probes to plan");
        if (probes.size() > discoveryCap(budget)) {
            throw new IllegalArgumentException(probes.size() + " food queries exceed the discovery share ("
                + (int) (DISCOVERY_SHARE * 100) + "%) of budget " + budget + " = " + discoveryCap(budget));
        }
        List<SnapshotPlan.Circle> region = List.of(
            new SnapshotPlan.Circle(area.centerLat(), area.centerLng(), area.radiusMeters() + AREA_MARGIN_METERS));
        return new SnapshotPlan(probes, region, probes.size(), List.of());
    }

    private SnapshotPlan.Priority priorityOf(PlaceCell cell) {
        if (cell == null) return SnapshotPlan.Priority.NEVER_CRAWLED;
        Instant indexed = cell.getLastIndexedAt();
        boolean stale = indexed == null || !indexed.isAfter(Instant.EPOCH)
            || indexed.isBefore(Instant.now().minus(Duration.ofDays(ingestionProperties.getFreshCooldownDays())));
        boolean sparse = cell.getPlaceCount() == null || cell.getPlaceCount() < ingestionProperties.getLowDensityThreshold();
        boolean failed = cell.getStatus() == PlaceCell.Status.FAILED;
        return stale || sparse || failed ? SnapshotPlan.Priority.SPARSE_OR_STALE : SnapshotPlan.Priority.CRAWLED;
    }

    private List<Candidate> cellsInArea(AreaPreset area) {
        double[] box = GeoUtils.boundingBox(area.centerLat(), area.centerLng(), area.radiusMeters());
        Map<String, Candidate> out = new LinkedHashMap<>();
        for (double lat = box[0]; lat <= box[2] + GRID_STEP_DEG / 2; lat += GRID_STEP_DEG) {
            for (double lng = box[1]; lng <= box[3] + GRID_STEP_DEG / 2; lng += GRID_STEP_DEG) {
                String key = GeoUtils.geohash(lat, lng, CellCoverageService.CELL_PRECISION);
                if (out.containsKey(key)) continue;
                double[] c = cellCoverageService.decodeCenter(key);
                double d = GeoUtils.distanceMeters(area.centerLat(), area.centerLng(), c[0], c[1]);
                if (d <= area.radiusMeters()) out.put(key, new Candidate(key, c[0], c[1], false, 0, d));
            }
        }
        return new ArrayList<>(out.values());
    }

    /** {@code given}: position in the explicit cell list (0 for area cells). */
    private record Candidate(String key, double lat, double lng, boolean explicit, int given, double distance) {}

    private record Ranked(Candidate candidate, SnapshotPlan.Priority priority) {}
}
