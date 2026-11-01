package com.foodmap.ingestion.snapshot;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.web.context.WebServerApplicationContext;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * The one-shot CLI snapshot ingestion run. Exists only under the
 * "snapshot-ingest" profile (application-snapshot-ingest.yml: no web server,
 * every automatic trigger off). Runs once after startup, then exits.
 *
 * Dry run (default) — plan + report, zero Goong requests:
 *   java -jar backend.jar --spring.profiles.active=dev,snapshot-ingest \
 *     --app.snapshot.areas=binh-thanh --app.snapshot.max-cells=3 --app.snapshot.budget=20
 *
 * Execute — add --app.snapshot.execute=true. Resume a report's pending work —
 * --app.snapshot.resume=ingestion-runs/snapshot-….json (instead of areas/cells).
 *
 * AUTO — --app.snapshot.auto=true --app.snapshot.budget=… (AutoRunCoordinator
 * chooses the food × area probes; options app.snapshot.auto.*). Also a dry
 * run unless --app.snapshot.execute=true.
 *
 * Exit code: 0 completed / dry run (AUTO also: ledger full, no eligible work,
 * max probes — with nothing pending), 3 stopped early or work pending,
 * 2 refused / locked / database safety (nothing more done), 1 error.
 */
@Slf4j
@Component
@Profile("snapshot-ingest")
@RequiredArgsConstructor
public class SnapshotIngestionRunner implements ApplicationRunner {

    private final SnapshotIngestionService service;
    private final AutoRunCoordinator autoCoordinator;
    private final AutoProperties autoProperties;
    private final SnapshotProperties properties;
    private final ApplicationContext context;
    private final Environment environment;
    private final SnapshotExit exit;

    @Override
    public void run(ApplicationArguments args) {
        List<String> problems = new ArrayList<>();
        if (context instanceof WebServerApplicationContext) {
            problems.add("the web server must not run (spring.main.web-application-type=none)");
        }
        if (!Arrays.asList(environment.getActiveProfiles()).contains("snapshot-ingest")) {
            problems.add("the snapshot-ingest profile must be active");
        }
        SnapshotRunReport report = properties.isAuto()
            ? autoCoordinator.run(properties, autoProperties, problems)
            : service.run(properties, problems);
        exit.exit(exitCode(report));
    }

    static int exitCode(SnapshotRunReport report) {
        int code = exitCode(report.getStopReason());
        boolean pending = report.getStopReason() != SnapshotRunReport.StopReason.DRY_RUN && !report.getPartialCells().isEmpty();
        return code == 0 && pending ? 3 : code;
    }

    static int exitCode(SnapshotRunReport.StopReason stop) {
        return switch (stop) {
            case DRY_RUN, COMPLETED, LEDGER_FULL, NO_ELIGIBLE_WORK, MAX_PROBES_REACHED -> 0;
            case REFUSED, LOCKED, DB_SAFETY -> 2;
            case ERROR -> 1;
            default -> 3;
        };
    }
}
