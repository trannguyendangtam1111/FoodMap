package com.foodmap.ingestion;

import com.foodmap.config.properties.AppProperties;
import com.foodmap.config.properties.IngestionProperties;
import com.foodmap.food.match.FoodClassifier;
import com.foodmap.ingestion.domain.PlaceCell;
import com.foodmap.ingestion.repository.PlaceCellRepository;
import com.foodmap.restaurant.domain.Restaurant;
import com.foodmap.restaurant.dto.DiscoveryResult;
import com.foodmap.restaurant.dto.RestaurantSummaryDTO;
import com.foodmap.restaurant.mapper.RestaurantMapper;
import com.foodmap.restaurant.ranking.RankingService;
import com.foodmap.restaurant.repository.RestaurantRepository;
import com.foodmap.restaurant.service.DiscoveryService;
import com.foodmap.scheduler.RestaurantSyncScheduler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.boot.context.properties.bind.PropertySourcesPlaceholdersResolver;

import java.util.HashMap;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Snapshot mode: Goong is a manually controlled ingestion source. Every
 * automatic trigger — startup seed, map viewport/nearby, nightly recrawl —
 * is OFF by default, so normal runtime reads PostgreSQL only. Each trigger
 * still works when explicitly enabled (the existing ingestion mechanism).
 */
class SnapshotModeTest {

    @Test
    void ingestionPropertiesDefaultEveryTriggerOff() {
        IngestionProperties p = new IngestionProperties();
        assertThat(p.isSeedOnStartup()).isFalse();
        assertThat(p.isViewportEnqueueEnabled()).isFalse();
        assertThat(p.isScheduledRecrawlEnabled()).isFalse();
    }

    /** The shipped application.yml, with no environment overrides, binds every trigger to false. */
    @Test
    void applicationYmlDefaultsEveryTriggerOff() throws Exception {
        MutablePropertySources sources = new MutablePropertySources();
        for (PropertySource<?> s : new YamlPropertySourceLoader().load("application", new ClassPathResource("application.yml"))) {
            sources.addLast(s);
        }
        // Placeholders resolve against the YAML only (its ${VAR:default} defaults), not this machine's env.
        Binder binder = new Binder(ConfigurationPropertySources.from(sources), new PropertySourcesPlaceholdersResolver(sources));
        IngestionProperties bound = binder.bind("app.ingestion", IngestionProperties.class).get();
        assertThat(bound.isSeedOnStartup()).isFalse();
        assertThat(bound.isViewportEnqueueEnabled()).isFalse();
        assertThat(bound.isScheduledRecrawlEnabled()).isFalse();
    }

    // ── Startup seed ─────────────────────────────────────────────────────────

    @Nested
    class Startup {
        IngestionQueue queue = mock(IngestionQueue.class);
        CellCoverageService coverage = mock(CellCoverageService.class);
        IngestionProperties props = new IngestionProperties();

        @Test
        void byDefaultStartupEnqueuesNothing() {
            new SeedCrawler(queue, coverage, props).seedOnStartup();
            verifyNoInteractions(queue, coverage);
        }

        @Test
        void explicitSeedRunStillEnqueuesSeedCells() {
            props.setSeedOnStartup(true);
            when(coverage.staleOrUnseen(any())).thenAnswer(inv -> Set.of("w3gv7b"));
            when(coverage.decodeCenter("w3gv7b")).thenReturn(new double[]{10.77, 106.70});
            when(queue.offer(any())).thenReturn(true);
            new SeedCrawler(queue, coverage, props).seedOnStartup();
            verify(queue).offer(new IngestionTask("w3gv7b", 10.77, 106.70, PlaceCell.Source.SEED));
        }
    }

    // ── Nightly recrawl ──────────────────────────────────────────────────────

    @Nested
    class Nightly {
        PlaceCellRepository cells = mock(PlaceCellRepository.class);
        IngestionQueue queue = mock(IngestionQueue.class);
        CellCoverageService coverage = mock(CellCoverageService.class);
        IngestionProperties props = new IngestionProperties();

        @Test
        void byDefaultTheScheduledRunDoesNothing() {
            new RestaurantSyncScheduler(cells, queue, coverage, props).refreshStaleCells();
            verifyNoInteractions(cells, queue, coverage);
        }

        @Test
        void explicitlyEnabledItStillRecrawlsTheOldestCells() {
            props.setScheduledRecrawlEnabled(true);
            PlaceCell old = PlaceCell.builder().cellKey("w3gv5x").centerLat(10.76).centerLng(106.68).build();
            when(cells.findStaleCells(any(), any(Pageable.class))).thenReturn(new PageImpl<>(List.of(old)));
            when(queue.offer(any())).thenReturn(true);
            new RestaurantSyncScheduler(cells, queue, coverage, props).refreshStaleCells();
            verify(queue).offer(new IngestionTask("w3gv5x", 10.76, 106.68, PlaceCell.Source.MANUAL));
        }
    }

    // ── Map browsing (viewport / nearby) ─────────────────────────────────────

    @Nested
    class MapBrowsing {
        RestaurantRepository restaurants = mock(RestaurantRepository.class);
        RestaurantMapper mapper = mock(RestaurantMapper.class);
        RankingService ranking = mock(RankingService.class);
        FoodClassifier classifier = mock(FoodClassifier.class);
        CellCoverageService coverage = mock(CellCoverageService.class);
        IngestionQueue queue = mock(IngestionQueue.class);
        ViewportEnqueueGate gate = mock(ViewportEnqueueGate.class);
        AppProperties app = new AppProperties();
        IngestionProperties props = new IngestionProperties();
        Restaurant pho = Restaurant.builder().name("Phở Hòa").latitude(10.7762).longitude(106.6892).build();
        RestaurantSummaryDTO phoDto = mock(RestaurantSummaryDTO.class);

        @BeforeEach
        void stubReads() {
            when(restaurants.findInEnvelope(anyDouble(), anyDouble(), anyDouble(), anyDouble(), anyInt())).thenReturn(List.of(pho));
            when(ranking.rank(any(), anyDouble(), anyDouble(), any())).thenReturn(List.of(pho));
            when(classifier.classifyAll(any())).thenReturn(new HashMap<>());   // unsaved fixture: null id
            when(mapper.toSummary(eq(pho), any())).thenReturn(phoDto);
        }

        DiscoveryService service() {
            return new DiscoveryService(restaurants, mapper, ranking, classifier, coverage, queue, gate, app, props);
        }

        @Test
        void byDefaultAViewportIsServedFromPostgresAndEnqueuesNothing() {
            DiscoveryResult<RestaurantSummaryDTO> result = service().getViewport(10.76, 106.68, 10.79, 106.71);
            assertThat(result.items()).containsExactly(phoDto);          // served from the database
            assertThat(result.cellsEnqueued()).isZero();
            verifyNoInteractions(coverage, gate, queue);                  // no cell lookup, no queue → no Goong
        }

        @Test
        void explicitlyEnabledAViewportStillEnqueuesStaleCells() {
            props.setViewportEnqueueEnabled(true);
            when(coverage.cellsInBbox(anyDouble(), anyDouble(), anyDouble(), anyDouble())).thenReturn(Set.of("w3gv7b"));
            when(coverage.staleOrUnseen(any())).thenReturn(Set.of("w3gv7b"));
            when(coverage.decodeCenter(anyString())).thenReturn(new double[]{10.77, 106.70});
            when(gate.tryEnqueue("w3gv7b")).thenReturn(true);
            when(queue.offer(any())).thenReturn(true);
            DiscoveryResult<RestaurantSummaryDTO> result = service().getViewport(10.76, 106.68, 10.79, 106.71);
            assertThat(result.cellsEnqueued()).isEqualTo(1);
            verify(queue, atLeastOnce()).offer(new IngestionTask("w3gv7b", 10.77, 106.70, PlaceCell.Source.VIEWPORT));
        }
    }
}
