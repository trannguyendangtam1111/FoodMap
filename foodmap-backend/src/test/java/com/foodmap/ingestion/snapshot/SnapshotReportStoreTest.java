package com.foodmap.ingestion.snapshot;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/** Reports and their summaries never carry a secret, whatever reached them. */
class SnapshotReportStoreTest {

    static final String KEY = "GoongKey-0123456789abcdef";

    @TempDir Path dir;

    @Test
    void secretsAreRedactedOnWriteRewriteAndSummary() throws Exception {
        SnapshotReportStore store = new SnapshotReportStore(new ObjectMapper(), dir);
        SnapshotRunReport report = new SnapshotRunReport();
        report.setStartedAt(Instant.now().toString());
        report.setStopReason(SnapshotRunReport.StopReason.ERROR);
        report.setStopDetail("I/O error on GET request for \"https://rsapi.goong.io/place/detail?place_id=x&api_key=" + KEY + "\"");

        Path file = store.write(report);
        assertThat(Files.readString(file)).doesNotContain(KEY).contains("api_key=***");
        // still a valid report (the ledger reads it)
        assertThat(store.read(file).getStopReason()).isEqualTo(SnapshotRunReport.StopReason.ERROR);

        report.setStopDetail("again ?key=" + KEY);
        store.rewrite(file, report);
        assertThat(Files.readString(file)).doesNotContain(KEY);

        Path md = store.writeSummary(file, "# run\n- detail: https://x?api_key=" + KEY);
        assertThat(md.getFileName().toString()).endsWith(".md");
        assertThat(Files.readString(md)).doesNotContain(KEY);
        assertThat(new SnapshotLedger(new ObjectMapper(), dir).read(Instant.now()).reportsRead()).isEqualTo(1);   // .md is not a report
    }
}
