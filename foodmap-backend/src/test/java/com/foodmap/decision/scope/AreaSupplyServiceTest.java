package com.foodmap.decision.scope;

import com.foodmap.catalog.AreaCatalogLoader;
import com.foodmap.catalog.AreaPreset;
import com.foodmap.catalog.AreaPresets;
import com.foodmap.restaurant.dto.AreaSupplyRow;
import com.foodmap.restaurant.repository.RestaurantRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static com.foodmap.decision.scope.LocationScopeParserTest.supplyRow;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/** The minimum-supply rule for discovery areas: one query for all areas, re-measured at most once a minute. */
class AreaSupplyServiceTest {

    private static final AreaPresets AREAS = AreaCatalogLoader.loadFromClasspath();

    private final RestaurantRepository repo = mock(RestaurantRepository.class);
    private final AtomicLong clock = new AtomicLong(0);
    private final AreaSupplyService service = new AreaSupplyService(repo, AREAS, clock::get);

    private void countsAre(long... perArea) {
        List<AreaSupplyRow> rows = new ArrayList<>();
        for (int i = 0; i < perArea.length; i++) {
            if (perArea[i] >= 0) rows.add(supplyRow(i + 1, perArea[i]));   // -1: row missing
        }
        when(repo.countRestaurantsWithinAreas(anyString(), anyString(), anyString())).thenReturn(rows);
    }

    @Test
    void allAreasAreMeasuredInOneQuery_inCatalogOrder() {
        countsAre(63, 55, 50, 56, 48, 48, 46, 61, 37, 76);
        assertThat(service.availableAreas()).extracting(AreaPreset::key).containsExactly(
            "quan-1", "quan-3", "quan-5", "quan-7", "quan-10", "binh-thanh", "phu-nhuan", "go-vap", "tan-binh", "thu-duc");

        ArgumentCaptor<String> lats = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> lngs = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> radii = ArgumentCaptor.forClass(String.class);
        verify(repo, times(1)).countRestaurantsWithinAreas(lats.capture(), lngs.capture(), radii.capture());
        assertThat(lats.getValue().split(",")).hasSize(10).startsWith("10.7737");
        assertThat(lngs.getValue().split(",")).hasSize(10).startsWith("106.6999");
        assertThat(radii.getValue()).isEqualTo("2000,1800,2000,3000,1800,2500,1800,3000,2500,4000");
    }

    @Test
    void fewerThan15Restaurants_isUnavailable_andNothingReplacesIt() {
        countsAre(63, 14, 15, 56, 0, 48, 46, 61, 37, -1);   // quan-3: 14, quan-10: 0, thu-duc: no row
        assertThat(service.availableAreas()).extracting(AreaPreset::key).containsExactly(
            "quan-1", "quan-5", "quan-7", "binh-thanh", "phu-nhuan", "go-vap", "tan-binh");
        assertThat(service.isAvailable(AREAS.get("quan-5").orElseThrow())).isTrue();    // exactly 15 is enough
        assertThat(service.isAvailable(AREAS.get("quan-3").orElseThrow())).isFalse();
        assertThat(service.supply()).containsEntry("thu-duc", 0).containsEntry("quan-3", 14);
    }

    @Test
    void supplyIsRemeasuredAtMostOncePerMinute_soDataChangesAreSeen() {
        countsAre(63, 55, 50, 56, 48, 48, 46, 61, 37, 76);
        service.availableAreas();
        clock.addAndGet(AreaSupplyService.TTL_NANOS - 1);
        service.availableAreas();
        service.isAvailable(AREAS.all().get(0));
        verify(repo, times(1)).countRestaurantsWithinAreas(anyString(), anyString(), anyString());

        countsAre(63, 55, 50, 56, 48, 48, 46, 3, 37, 76);   // go-vap lost its restaurants
        clock.addAndGet(1);
        assertThat(service.availableAreas()).extracting(AreaPreset::key).doesNotContain("go-vap").hasSize(9);
        verify(repo, times(2)).countRestaurantsWithinAreas(anyString(), anyString(), anyString());
        verifyNoMoreInteractions(repo);
    }

    @Test
    void supply_isKeyedByArea() {
        countsAre(1, 2, 3, 4, 5, 6, 7, 8, 9, 10);
        Map<String, Integer> supply = service.supply();
        assertThat(supply).hasSize(10).containsEntry("quan-1", 1).containsEntry("thu-duc", 10);
    }
}
