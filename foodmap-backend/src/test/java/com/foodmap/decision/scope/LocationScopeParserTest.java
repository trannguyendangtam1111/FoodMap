package com.foodmap.decision.scope;

import com.foodmap.catalog.AreaCatalogLoader;
import com.foodmap.catalog.AreaPresets;
import com.foodmap.common.exception.BadRequestException;
import com.foodmap.config.properties.DecisionProperties;
import com.foodmap.restaurant.dto.AreaSupplyRow;
import com.foodmap.restaurant.repository.RestaurantRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * The one scope interpretation shared by every /api/v1/decisions endpoint:
 * USER (lat+lng[+radius]), AREA (area=key) or CITY_CENTER (nothing), and
 * every contradictory or malformed combination as a 400.
 */
class LocationScopeParserTest {

    private static final AreaPresets AREAS = AreaCatalogLoader.loadFromClasspath();

    private final DecisionProperties props = new DecisionProperties();
    private RestaurantRepository restaurantRepository;
    private LocationScopeParser parser;

    /** Every area holds 40 restaurants except go-vap (12: below the minimum of 15). */
    @BeforeEach
    void setUp() {
        restaurantRepository = mock(RestaurantRepository.class);
        when(restaurantRepository.countRestaurantsWithinAreas(anyString(), anyString(), anyString())).thenAnswer(inv -> {
            List<AreaSupplyRow> rows = new ArrayList<>();
            for (int i = 0; i < AREAS.all().size(); i++) {
                long count = AREAS.all().get(i).key().equals("go-vap") ? 12 : 40;
                rows.add(supplyRow(i + 1, count));
            }
            return rows;
        });
        parser = new LocationScopeParser(props, AREAS, new AreaSupplyService(restaurantRepository, AREAS));
    }

    static AreaSupplyRow supplyRow(long index, long count) {
        return new AreaSupplyRow() {
            public Long getAreaIndex() { return index; }
            public Long getRestaurantCount() { return count; }
        };
    }

    private LocationScope parse(String lat, String lng, String radius, String area) {
        return parser.parse(lat, lng, radius, area);
    }

    // ── CITY_CENTER ──────────────────────────────────────────────────────────

    @Test
    void nothing_isTheCityCentre_3kmWidenedTo5km_noDistance() {
        LocationScope s = parse(null, null, null, null);
        assertThat(s).isInstanceOf(LocationScope.CityCenter.class);
        assertThat(s.source()).isEqualTo("CITY_CENTER");
        assertThat(s.ladder().stages()).containsExactly(3000, 5000);
        assertThat(s.label()).isEqualTo("Quanh trung tâm TP.HCM");
        assertThat(s.showsDistance()).isFalse();
        assertThat(s.area()).isNull();
        assertThat(parse(" ", "", "  ", "")).isInstanceOf(LocationScope.CityCenter.class);   // blank = absent (V1)
        verifyNoInteractions(restaurantRepository);
    }

    // ── USER ─────────────────────────────────────────────────────────────────

    @Test
    void latLng_isUser_default1km_widenedOnceTo3km() {
        LocationScope s = parse("10.7769", "106.7009", null, null);
        assertThat(s).isInstanceOf(LocationScope.User.class);
        assertThat(s.source()).isEqualTo("USER");
        assertThat(s.originLat()).isEqualTo(10.7769);
        assertThat(s.originLng()).isEqualTo(106.7009);
        assertThat(s.ladder().stages()).containsExactly(1000, 3000);
        assertThat(s.showsDistance()).isTrue();
        assertThat(s.label()).isEqualTo("Gần bạn");
        verifyNoInteractions(restaurantRepository);
    }

    @ParameterizedTest(name = "radius={0} → {1}")
    @CsvSource(delimiter = '|', value = {"800|800,2400", "1000|1000,3000", "2000|2000,5000", "4000|4000,5000", "5000|5000"})
    void userRadius_widensOnceByThree_neverBeyond5km(String radius, String expected) {
        List<Integer> stages = java.util.Arrays.stream(expected.split(",")).map(Integer::valueOf).toList();
        assertThat(parse("10.7", "106.7", radius, null).ladder().stages()).isEqualTo(stages);
    }

    @Test
    void userScope_neverPrintsItsCoordinates() {
        LocationScope s = parse("10.781234", "106.701234", "1500", null);
        assertThat(s.toString()).doesNotContain("10.78", "106.70").contains("1500");
    }

    @ParameterizedTest(name = "lat={0}")
    @CsvSource({"90.0001", "-90.5", "abc", "NaN", "Infinity"})
    void invalidLatitude_isRejected(String lat) {
        assertThatThrownBy(() -> parse(lat, "106.7", null, null))
            .isInstanceOf(BadRequestException.class).hasMessageContaining("lat");
    }

    @ParameterizedTest(name = "lng={0}")
    @CsvSource({"180.5", "-181", "east"})
    void invalidLongitude_isRejected(String lng) {
        assertThatThrownBy(() -> parse("10.7", lng, null, null))
            .isInstanceOf(BadRequestException.class).hasMessageContaining("lng");
    }

    @Test
    void latWithoutLng_andLngWithoutLat_areRejected() {
        assertThatThrownBy(() -> parse("10.7", null, null, null))
            .isInstanceOf(BadRequestException.class).hasMessage("lat and lng must be provided together.");
        assertThatThrownBy(() -> parse(null, "106.7", null, null))
            .isInstanceOf(BadRequestException.class).hasMessage("lat and lng must be provided together.");
    }

    @ParameterizedTest(name = "radius={0}")
    @CsvSource({"0", "-5"})
    void nonPositiveRadius_isRejected(String radius) {
        assertThatThrownBy(() -> parse("10.7", "106.7", radius, null))
            .isInstanceOf(BadRequestException.class).hasMessageContaining("positive");
    }

    @Test
    void radiusAboveMaximum_isRejected_atMaximumAccepted() {
        assertThatThrownBy(() -> parse("10.7", "106.7", "5001", null))
            .isInstanceOf(BadRequestException.class).hasMessageContaining("5000");
        assertThat(parse("10.7", "106.7", "5000", null).ladder().base()).isEqualTo(5000);
    }

    @ParameterizedTest(name = "radius={0}")
    @CsvSource({"abc", "1000.5", "99999999999"})
    void nonIntegerRadius_isRejected(String radius) {
        assertThatThrownBy(() -> parse("10.7", "106.7", radius, null))
            .isInstanceOf(BadRequestException.class).hasMessageContaining("radius");
    }

    @Test
    void errorMessages_neverEchoCoordinates() {
        assertThatThrownBy(() -> parse("10.781234", null, null, null)).hasMessageNotContaining("10.78");
        assertThatThrownBy(() -> parse("10.781234", "106.701234", null, "quan-1"))
            .hasMessageNotContaining("10.78").hasMessageNotContaining("106.70");
        assertThatThrownBy(() -> parse(null, null, null, "10.781234,106.701234")).hasMessage("Unknown area.");
    }

    // ── AREA ─────────────────────────────────────────────────────────────────

    @Test
    void area_isTheServersArea_ownRadius_neverWidened_noDistance() {
        LocationScope s = parse(null, null, null, "binh-thanh");
        assertThat(s).isInstanceOf(LocationScope.Area.class);
        assertThat(s.source()).isEqualTo("AREA");
        assertThat(s.area().key()).isEqualTo("binh-thanh");
        assertThat(s.label()).isEqualTo("Quanh Bình Thạnh");
        assertThat(s.ladder().stages()).containsExactly(2500);       // one stage: an area never widens
        assertThat(s.originLat()).isEqualTo(10.8024);                 // server-owned centre
        assertThat(s.originLng()).isEqualTo(106.7136);
        assertThat(s.showsDistance()).isFalse();
        assertThat(s.toString()).isEqualTo("Area[binh-thanh]");
        assertThat(parse(null, null, null, " quan-1 ").area().key()).isEqualTo("quan-1");
    }

    @Test
    void unknownArea_isRejected() {
        assertThatThrownBy(() -> parse(null, null, null, "quan-99"))
            .isInstanceOf(BadRequestException.class).hasMessage("Unknown area 'quan-99'.");
        assertThatThrownBy(() -> parse(null, null, null, "Quan-1"))     // keys are exact slugs
            .isInstanceOf(BadRequestException.class);
    }

    @Test
    void areaBelowTheRestaurantMinimum_isUnavailable_neverReplaced() {
        assertThatThrownBy(() -> parse(null, null, null, "go-vap"))
            .isInstanceOf(BadRequestException.class)
            .hasMessage("Area 'go-vap' is not available right now; choose another area.");
    }

    @Test
    void areaWithLatLng_isRejected() {
        assertThatThrownBy(() -> parse("10.7", "106.7", null, "quan-1"))
            .isInstanceOf(BadRequestException.class).hasMessageContaining("not both");
        assertThatThrownBy(() -> parse("10.7", null, null, "quan-1"))   // incomplete location: still a 400
            .isInstanceOf(BadRequestException.class);
    }

    @Test
    void radiusWithAreaOrWithoutLocation_isRejected() {
        assertThatThrownBy(() -> parse(null, null, "1000", "quan-1"))
            .isInstanceOf(BadRequestException.class).hasMessageContaining("an area has its own radius");
        assertThatThrownBy(() -> parse(null, null, "1000", null))
            .isInstanceOf(BadRequestException.class).hasMessage("radius needs lat and lng.");
    }
}
