package com.foodmap.ingestion.snapshot;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.foodmap.common.security.SecretRedactor;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Snapshot run reports on disk: one JSON file per run in the report
 * directory. Also the run-to-run memory of rejected place_ids (no new table):
 * every report's rejected list is read before a run.
 */
@Slf4j
public class SnapshotReportStore {

    private static final DateTimeFormatter FILE_TIME =
        DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss.SSS'Z'").withZone(ZoneOffset.UTC);

    private final ObjectMapper mapper;
    private final Path dir;

    public SnapshotReportStore(ObjectMapper mapper, Path dir) {
        this.mapper = mapper.copy().enable(SerializationFeature.INDENT_OUTPUT);
        this.dir = dir;
    }

    public Path write(SnapshotRunReport report) {
        try {
            Files.createDirectories(dir);
            Path file = dir.resolve("snapshot-" + FILE_TIME.format(Instant.now()) + ".json");
            while (Files.exists(file)) {   // two reports in the same millisecond (tests)
                file = dir.resolve("snapshot-" + FILE_TIME.format(Instant.now()) + "-" + System.nanoTime() + ".json");
            }
            writeJson(file, report);
            return file;
        } catch (IOException e) {
            throw new UncheckedIOException("Could not write snapshot report to " + dir, e);
        }
    }

    /** Replaces a report written earlier by this run (the IN_PROGRESS marker becomes the final report). */
    public Path rewrite(Path file, SnapshotRunReport report) {
        try {
            writeJson(file, report);
            return file;
        } catch (IOException e) {
            throw new UncheckedIOException("Could not write snapshot report " + file, e);
        }
    }

    /** A human-readable companion of a report (same name, .md) — not read by the ledger (only *.json is). */
    public Path writeSummary(Path reportFile, String markdown) {
        String name = reportFile.getFileName().toString().replaceFirst("\\.json$", "") + ".md";
        Path file = reportFile.resolveSibling(name);
        try {
            Files.writeString(file, SecretRedactor.redact(markdown), StandardCharsets.UTF_8);
            return file;
        } catch (IOException e) {
            throw new UncheckedIOException("Could not write snapshot summary " + file, e);
        }
    }

    /** Reports never carry a secret: whatever reached them (an exception message …) is redacted. */
    private void writeJson(Path file, SnapshotRunReport report) throws IOException {
        Files.writeString(file, SecretRedactor.redact(mapper.writeValueAsString(report)), StandardCharsets.UTF_8);
    }

    public SnapshotRunReport read(Path file) {
        try {
            return mapper.readValue(file.toFile(), SnapshotRunReport.class);
        } catch (IOException e) {
            throw new IllegalArgumentException("Could not read snapshot report " + file + ": " + e.getMessage(), e);
        }
    }

    /** Every place_id any earlier report rejected — never fetched again. */
    public Set<String> loadRejectedIds() {
        Set<String> ids = new LinkedHashSet<>();
        if (!Files.isDirectory(dir)) return ids;
        try (DirectoryStream<Path> files = Files.newDirectoryStream(dir, "*.json")) {
            for (Path file : files) {
                try {
                    read(file).getRejected().forEach(r -> ids.add(r.getPlaceId()));
                } catch (IllegalArgumentException e) {
                    log.warn("Skipping unreadable snapshot report {}: {}", file, e.getMessage());
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Could not list snapshot reports in " + dir, e);
        }
        return ids;
    }
}
