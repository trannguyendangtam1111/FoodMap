package com.foodmap.ingestion.snapshot;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.Appender;
import ch.qos.logback.core.OutputStreamAppender;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.foodmap.common.security.LogRedactionInstaller;
import com.foodmap.integration.goong.GoongClient;
import com.foodmap.integration.goong.GoongRequestBudget;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.mock.mockito.MockReset;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * The AUTO command as it would be launched — WITHOUT the execute flag:
 * flags bound by Spring Boot, the runner dispatching to AUTO, the real
 * catalog and area presets, a dry run, zero Goong requests, exit 0. Goong
 * (a mock that must never be called), the advisory lock, the PostGIS
 * coverage query and the database identity (H2 here) are replaced.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles({"test", "snapshot-ingest"})
class AutoRunnerIntegrationTest {

    static final Path REPORTS;

    static {
        try {
            REPORTS = Files.createTempDirectory("auto-runs");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @DynamicPropertySource
    static void autoRun(DynamicPropertyRegistry registry) {
        registry.add("app.snapshot.auto", () -> "true");
        registry.add("app.snapshot.budget", () -> "40");
        registry.add("app.snapshot.auto.areas", () -> "quan-3");
        registry.add("app.snapshot.report-dir", REPORTS::toString);
    }

    // The run happens at startup: its interactions must survive the reset between test methods.
    @MockBean(reset = MockReset.NONE) GoongClient goongClient;
    @MockBean(reset = MockReset.NONE) SnapshotExit exit;

    @Autowired GoongRequestBudget budget;
    @Autowired ObjectMapper objectMapper;

    @Test
    void theAutoCommandDryRunsWithZeroGoongRequests() throws IOException {
        verify(exit).exit(0);
        verifyNoInteractions(goongClient);
        assertThat(budget.isArmed()).isFalse();

        List<Path> files;
        try (Stream<Path> s = Files.list(REPORTS)) {
            files = s.filter(f -> f.toString().endsWith(".json")).toList();
        }
        assertThat(files).hasSize(1);
        SnapshotRunReport report = objectMapper.readValue(files.get(0).toFile(), SnapshotRunReport.class);
        assertThat(report.getStopReason()).isEqualTo(SnapshotRunReport.StopReason.DRY_RUN);
        assertThat(report.getAuto()).isNotNull();
        assertThat(report.getAuto().getConfig().getEffectiveBudget()).isEqualTo(40);
        assertThat(report.getAuto().getConfig().getExcludeFoods()).contains("mi", "kem");
        assertThat(report.getAuto().getPlannedWaves()).isNotEmpty()
            .allSatisfy(w -> assertThat(w.getArea()).isEqualTo("quan-3"));
        assertThat(report.getRequests().getUsed()).isZero();
        assertThat(Files.exists(REPORTS.resolve(report.getAuto().getSummaryFile()))).isTrue();
    }

    @Test
    void logRedactionIsInstalledOnTheConsole() {
        LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
        Appender<ILoggingEvent> console = context.getLogger(Logger.ROOT_LOGGER_NAME).getAppender("CONSOLE");
        assertThat(console).isInstanceOf(OutputStreamAppender.class);
        assertThat(((OutputStreamAppender<ILoggingEvent>) console).getEncoder())
            .isInstanceOf(LogRedactionInstaller.RedactingEncoder.class);
    }

    /** The lock, the coverage query (PostGIS) and the database identity, for H2. */
    @TestConfiguration
    static class Stubs {
        @Bean
        @Primary
        SnapshotRunLock freeLock() {
            return () -> Optional.of(() -> { });
        }

        @Bean
        @Primary
        CoverageReader emptyCoverage() {
            return (areas, foods) -> {
                Map<String, Map<String, Integer>> out = new LinkedHashMap<>();
                areas.forEach(a -> out.put(a.key(), new LinkedHashMap<>()));
                return out;
            };
        }

        @Bean
        @Primary
        SnapshotDbGuard devDatabase() {
            return new SnapshotDbGuard() {
                @Override public String identity() { return "localhost:5434/foodmap_dev"; }
                @Override public Baseline baseline() { throw new AssertionError("a dry run takes no baseline"); }
                @Override public List<String> verify(Baseline b, int created, int rekeyed) { throw new AssertionError(); }
            };
        }
    }
}
