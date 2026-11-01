package com.foodmap.ingestion.snapshot;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.foodmap.integration.goong.GoongClient;
import com.foodmap.integration.goong.GoongRequestBudget;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.ApplicationContext;
import org.springframework.boot.web.context.WebServerApplicationContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * The real "snapshot-ingest" wiring: the runner runs once at startup, as a
 * DRY RUN (no execute flag) over a real area preset, writes its report,
 * makes zero Goong requests, and asks to exit 0. Goong (a mock that must
 * never be called), the advisory lock (PostgreSQL-only SQL; H2 here) and the
 * JVM exit are replaced.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles({"test", "snapshot-ingest"})
class SnapshotIngestionRunnerIntegrationTest {

    static final Path REPORTS;

    static {
        try {
            REPORTS = Files.createTempDirectory("snapshot-runs");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @DynamicPropertySource
    static void snapshotRun(DynamicPropertyRegistry registry) {
        registry.add("app.snapshot.areas", () -> "binh-thanh");
        registry.add("app.snapshot.max-cells", () -> "3");
        registry.add("app.snapshot.budget", () -> "20");
        registry.add("app.snapshot.report-dir", REPORTS::toString);
    }

    @MockBean GoongClient goongClient;
    @MockBean SnapshotExit exit;

    @Autowired GoongRequestBudget budget;
    @Autowired ApplicationContext context;
    @Autowired ObjectMapper objectMapper;

    @Test
    void theRunnerDryRunsOnceWithZeroGoongRequestsAndExits() throws IOException {
        verify(exit).exit(0);
        verifyNoInteractions(goongClient);
        assertThat(budget.isArmed()).isFalse();
        assertThat(context).isNotInstanceOf(WebServerApplicationContext.class);

        List<Path> files;
        try (Stream<Path> s = Files.list(REPORTS)) {
            files = s.toList();
        }
        assertThat(files).hasSize(1);
        SnapshotRunReport report = objectMapper.readValue(files.get(0).toFile(), SnapshotRunReport.class);
        assertThat(report.getStopReason()).isEqualTo(SnapshotRunReport.StopReason.DRY_RUN);
        assertThat(report.getMode()).isEqualTo(SnapshotRunReport.Mode.DRY_RUN);
        assertThat(report.getPlannedCells()).hasSize(3)
            .allSatisfy(c -> assertThat(c.getPriority()).isEqualTo(SnapshotPlan.Priority.NEVER_CRAWLED));
        assertThat(report.getDiscoveryCap()).isEqualTo(6);
        assertThat(report.getDetailReserve()).isEqualTo(14);
        assertThat(report.getRequests().getUsed()).isZero();
    }

    /** The PostgreSQL advisory lock cannot run on H2: a free lock instead (the lock itself has its own test). */
    @TestConfiguration
    static class FreeLock {
        @Bean
        @Primary
        SnapshotRunLock freeLock() {
            return () -> Optional.of(() -> { });
        }
    }
}
