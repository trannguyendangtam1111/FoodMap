package com.foodmap.ingestion;

import com.foodmap.common.exception.BudgetExhaustedException;
import com.foodmap.config.properties.IngestionProperties;
import com.foodmap.ingestion.snapshot.SnapshotIngestionRunner;
import com.foodmap.ingestion.snapshot.SnapshotIngestionService;
import com.foodmap.integration.goong.GoongClient;
import com.foodmap.integration.goong.GoongRequestBudget;
import com.foodmap.restaurant.service.RestaurantSyncService;
import com.foodmap.scheduler.RestaurantSyncScheduler;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * Snapshot mode, with the real application context: starting the app (and
 * the nightly job firing) makes no Goong call and queues nothing, while the
 * explicit ingestion machinery is still wired and available.
 *
 * The test profile turns the seed off explicitly; the viewport and nightly
 * triggers here come from application.yml's own defaults (and
 * SnapshotModeTest checks the YAML seed default directly).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@ActiveProfiles("test")
class SnapshotModeStartupIntegrationTest {

    @MockBean GoongClient goongClient;

    @Autowired IngestionProperties properties;
    @Autowired IngestionQueue queue;
    @Autowired RestaurantSyncScheduler scheduler;
    @Autowired SeedCrawler seedCrawler;
    @Autowired IngestionWorker worker;
    @Autowired KeywordFanoutService fanout;
    @Autowired RestaurantSyncService sync;
    @Autowired GoongRequestBudget budget;
    @Autowired ApplicationContext context;

    @Test
    void startupMakesNoGoongCallAndQueuesNothing() {
        assertThat(properties.isViewportEnqueueEnabled()).isFalse();
        assertThat(properties.isScheduledRecrawlEnabled()).isFalse();
        assertThat(queue.queuedSize()).isZero();
        assertThat(queue.inFlightSize()).isZero();
        verifyNoInteractions(goongClient);
    }

    @Test
    void theNightlyJobFiringIsANoOp() {
        scheduler.refreshStaleCells();
        assertThat(queue.queuedSize()).isZero();
        verifyNoInteractions(goongClient);
    }

    @Test
    void theExplicitIngestionMechanismIsStillAvailable() {
        assertThat(seedCrawler).isNotNull();
        assertThat(worker).isNotNull();
        assertThat(fanout).isNotNull();
        assertThat(sync).isNotNull();
    }

    @Test
    void outsideTheSnapshotRunnerGoongIsDeniedAndTheRunnerDoesNotExist() {
        assertThat(budget.isArmed()).isFalse();
        assertThatThrownBy(() -> budget.charge("autoComplete", 1)).isInstanceOf(BudgetExhaustedException.class);
        assertThat(context.getBeanNamesForType(SnapshotIngestionRunner.class)).isEmpty();
        assertThat(context.getBeanNamesForType(SnapshotIngestionService.class)).isEmpty();
    }
}
