package com.foodmap.ingestion.snapshot;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.foodmap.integration.goong.GoongRequestBudget;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The run-to-run memory of snapshot ingestion, read from the report files in
 * the report directory (no table, no other store):
 *
 *   usage      Goong HTTP attempts of runs that ended within the last 24 hours
 *              (each run once, however many copies of its report exist; a run
 *              left IN_PROGRESS — the process died — counts its whole budget)
 *   completed  food × area pairs an earlier run probed to the end
 *   pending    food × area pairs an earlier run left partial (and not completed since)
 *
 * Fails closed: a *.json that cannot be read, or whose usage or time cannot be
 * established, is listed as unreadable — never skipped, as skipping it could
 * undercount usage. Files are read in name order; the result is deterministic.
 */
public final class SnapshotLedger {

    static final Duration WINDOW = Duration.ofHours(24);

    /**
     * @param usageInWindow  Goong HTTP attempts recorded in the last 24 h
     * @param pendingPairs   pair → the report file that left it pending (resume from it)
     */
    public record State(int reportsRead, int usageInWindow, Set<String> completedPairs,
                        Map<String, String> pendingPairs, List<String> unreadable) {}

    private final ObjectMapper mapper;
    private final Path dir;

    public SnapshotLedger(ObjectMapper mapper, Path dir) {
        this.mapper = mapper;
        this.dir = dir;
    }

    public State read(Instant now) {
        List<Path> files = new ArrayList<>();
        if (Files.isDirectory(dir)) {
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir, "*.json")) {
                stream.forEach(files::add);
            } catch (IOException e) {
                throw new UncheckedIOException("Could not list snapshot reports in " + dir, e);
            }
        }
        files.sort(null);

        Instant cutoff = now.minus(WINDOW);
        Map<String, Integer> usageByRun = new HashMap<>();
        Set<String> completed = new LinkedHashSet<>();
        Map<String, String> pending = new LinkedHashMap<>();
        List<String> unreadable = new ArrayList<>();

        for (Path file : files) {
            String name = file.getFileName().toString();
            JsonNode node;
            SnapshotRunReport report;
            try {
                node = mapper.readTree(file.toFile());
                report = mapper.treeToValue(node, SnapshotRunReport.class);
            } catch (IOException | IllegalArgumentException e) {
                unreadable.add(name + " (not a readable report: " + e.getClass().getSimpleName() + ")");
                continue;
            }
            JsonNode used = node.path("requests").path("used");
            Instant started = instant(report.getStartedAt());
            Instant finished = instant(report.getFinishedAt());
            if (!used.isInt() || used.asInt() < 0 || started == null || report.getStopReason() == null
                    || (report.getFinishedAt() != null && finished == null)) {
                unreadable.add(name + " (missing or invalid requests.used, startedAt, finishedAt or stopReason)");
                continue;
            }

            int usage = used.asInt();
            if (report.getStopReason() == SnapshotRunReport.StopReason.IN_PROGRESS) {
                int budget = report.getBudget() == null ? GoongRequestBudget.MAX_LIMIT : report.getBudget();
                usage = Math.max(usage, budget);   // the run may have spent all of it
            }
            Instant when = finished != null ? finished : started;
            if (!when.isBefore(cutoff)) {
                usageByRun.merge(runIdentity(report), usage, Math::max);   // copies of one run count once
            }

            completed.addAll(report.getCompletedProbes());
            for (SnapshotRunReport.PartialCell partial : report.getPartialCells()) {
                if (partial.getFoodKey() != null) pending.putIfAbsent(partial.getCellKey(), name);
            }
        }
        completed.forEach(pending::remove);
        int usage = usageByRun.values().stream().mapToInt(Integer::intValue).sum();
        return new State(files.size(), usage, completed, pending, unreadable);
    }

    /** The run's id; for reports written before run ids, its start and end times, mode and budget. */
    static String runIdentity(SnapshotRunReport report) {
        if (report.getRunId() != null) return report.getRunId();
        return report.getStartedAt() + "|" + report.getFinishedAt() + "|" + report.getMode() + "|" + report.getBudget();
    }

    private static Instant instant(String text) {
        if (text == null) return null;
        try {
            return Instant.parse(text);
        } catch (DateTimeParseException e) {
            return null;
        }
    }
}
