package com.foodmap.ingestion.snapshot;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.foodmap.integration.goong.GoongClient;
import com.foodmap.integration.goong.GoongRequestBudget;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
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
 * The food-probe experiment as it would be launched — but WITHOUT the execute
 * flag: the real catalog and area presets, a dry run, zero Goong requests,
 * exit 0. Goong (a mock that must never be called), the advisory lock (H2
 * here) and the JVM exit are replaced.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles({"test", "snapshot-ingest"})
class SnapshotFoodProbeRunnerIntegrationTest {

    static final Path REPORTS;

    static {
        try {
            REPORTS = Files.createTempDirectory("food-probe-runs");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @DynamicPropertySource
    static void foodProbeRun(DynamicPropertyRegistry registry) {
        registry.add("app.snapshot.areas", () -> "binh-thanh");
        registry.add("app.snapshot.foods", () -> "pho,com,lau");
        registry.add("app.snapshot.budget", () -> "20");
        registry.add("app.snapshot.report-dir", REPORTS::toString);
    }

    @MockBean GoongClient goongClient;
    @MockBean SnapshotExit exit;

    @Autowired GoongRequestBudget budget;
    @Autowired ObjectMapper objectMapper;

    @Test
    void theFoodProbeExperimentDryRunsWithTheRealCatalogAndZeroGoongRequests() throws IOException {
        verify(exit).exit(0);
        verifyNoInteractions(goongClient);
        assertThat(budget.isArmed()).isFalse();

        List<Path> files;
        try (Stream<Path> s = Files.list(REPORTS)) {
            files = s.toList();
        }
        assertThat(files).hasSize(1);
        SnapshotRunReport report = objectMapper.readValue(files.get(0).toFile(), SnapshotRunReport.class);
        assertThat(report.getStopReason()).isEqualTo(SnapshotRunReport.StopReason.DRY_RUN);
        assertThat(report.getDiscovery()).isEqualTo(SnapshotRunReport.Discovery.FOOD_PROBES);
        assertThat(report.getPlannedCells()).extracting(SnapshotRunReport.PlannedCell::getKeywords)
            .containsExactly(List.of("Phở"), List.of("Cơm"), List.of("Lẩu"));
        assertThat(report.getPlannedCells()).allSatisfy(c -> assertThat(c.getRadiusMeters()).isEqualTo(2500));
        assertThat(report.getDiscoveryCap()).isEqualTo(3);
        assertThat(report.getDetailReserve()).isEqualTo(17);
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
