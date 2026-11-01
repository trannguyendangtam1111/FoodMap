package com.foodmap.ingestion.snapshot;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;

/** The report-file ledger: rolling 24 h Goong usage, completed / pending food × area pairs, fail-closed reading. */
class SnapshotLedgerTest {

    static final Instant NOW = Instant.parse("2026-10-06T12:00:00Z");

    @TempDir Path dir;
    final ObjectMapper mapper = new ObjectMapper();

    SnapshotLedger.State read() {
        return new SnapshotLedger(mapper, dir).read(NOW);
    }

    void report(String file, Consumer<SnapshotRunReport> fill) throws Exception {
        SnapshotRunReport r = new SnapshotRunReport();
        r.setRunId(file);
        r.setMode(SnapshotRunReport.Mode.EXECUTE);
        r.setStopReason(SnapshotRunReport.StopReason.COMPLETED);
        r.setBudget(40);
        r.setStartedAt(NOW.minus(Duration.ofHours(1)).toString());
        r.setFinishedAt(NOW.minus(Duration.ofMinutes(59)).toString());
        fill.accept(r);
        mapper.writeValue(dir.resolve(file).toFile(), r);
    }

    static Consumer<SnapshotRunReport> used(int n) {
        return r -> r.getRequests().setUsed(n);
    }

    @Test
    void noReportsMeansNoPriorUsage() {
        SnapshotLedger.State state = read();
        assertThat(state.usageInWindow()).isZero();
        assertThat(state.reportsRead()).isZero();
        assertThat(state.unreadable()).isEmpty();
    }

    @Test
    void runsThatEndedWithinTheLast24HoursCountOlderOnesDoNot() throws Exception {
        report("a.json", used(30));                                            // 59 minutes ago
        report("b.json", used(17).andThen(r -> r.setFinishedAt(NOW.minus(Duration.ofHours(23)).toString())));
        report("c.json", used(50).andThen(r -> {                              // ended 25 h ago
            r.setStartedAt(NOW.minus(Duration.ofHours(26)).toString());
            r.setFinishedAt(NOW.minus(Duration.ofHours(25)).toString());
        }));
        report("d.json", used(9).andThen(r -> {                               // started 24h01m ago, ended inside the window
            r.setStartedAt(NOW.minus(Duration.ofMinutes(24 * 60 + 1)).toString());
            r.setFinishedAt(NOW.minus(Duration.ofMinutes(24 * 60 - 1)).toString());
        }));

        assertThat(read().usageInWindow()).isEqualTo(30 + 17 + 9);
        assertThat(read().reportsRead()).isEqualTo(4);
    }

    @Test
    void copiesOfOneRunAreCountedOnce() throws Exception {
        report("run.json", used(18));
        Files.copy(dir.resolve("run.json"), dir.resolve("run-copy.json"));
        // a report written before run ids: identified by its times, mode and budget
        report("legacy.json", used(8).andThen(r -> r.setRunId(null)));
        Files.copy(dir.resolve("legacy.json"), dir.resolve("legacy-copy.json"));

        assertThat(read().usageInWindow()).isEqualTo(18 + 8);
    }

    @Test
    void aRunLeftInProgressCountsItsWholeBudget() throws Exception {
        report("crashed.json", used(3).andThen(r -> {
            r.setStopReason(SnapshotRunReport.StopReason.IN_PROGRESS);
            r.setFinishedAt(null);
        }));
        assertThat(read().usageInWindow()).isEqualTo(40);
    }

    @Test
    void unreadableReportsAreListedNeverSkippedSilently() throws Exception {
        report("ok.json", used(5));
        Files.writeString(dir.resolve("broken.json"), "{ not json");
        Files.writeString(dir.resolve("no-usage.json"),
            "{\"startedAt\":\"" + NOW + "\",\"stopReason\":\"COMPLETED\"}");
        Files.writeString(dir.resolve("no-start.json"),
            "{\"requests\":{\"used\":4},\"stopReason\":\"COMPLETED\"}");
        Files.writeString(dir.resolve("notes.txt"), "ignored: not a report");

        SnapshotLedger.State state = read();
        assertThat(state.unreadable()).hasSize(3)
            .anySatisfy(u -> assertThat(u).startsWith("broken.json"))
            .anySatisfy(u -> assertThat(u).startsWith("no-usage.json"))
            .anySatisfy(u -> assertThat(u).startsWith("no-start.json"));
        assertThat(state.usageInWindow()).isEqualTo(5);
    }

    @Test
    void completedAndPendingFoodAreaPairs() throws Exception {
        SnapshotRunReport.PartialCell pho = new SnapshotRunReport.PartialCell();
        pho.setCellKey("food:pho@quan-5");
        pho.setFoodKey("pho");
        SnapshotRunReport.PartialCell com = new SnapshotRunReport.PartialCell();
        com.setCellKey("food:com@quan-5");
        com.setFoodKey("com");
        SnapshotRunReport.PartialCell mapCell = new SnapshotRunReport.PartialCell();
        mapCell.setCellKey("w3gvkq");
        report("1.json", used(10).andThen(r -> r.setPartialCells(List.of(pho, com, mapCell))));
        report("2.json", used(3).andThen(r -> r.setCompletedProbes(List.of("food:pho@quan-5", "food:bun@quan-5"))));

        SnapshotLedger.State state = read();
        assertThat(state.completedPairs()).containsExactlyInAnyOrder("food:pho@quan-5", "food:bun@quan-5");
        assertThat(state.pendingPairs()).containsOnlyKeys("food:com@quan-5");   // pho was finished by a resume
        assertThat(state.pendingPairs().get("food:com@quan-5")).isEqualTo("1.json");
    }
}
