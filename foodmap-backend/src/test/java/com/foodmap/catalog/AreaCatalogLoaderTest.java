package com.foodmap.catalog;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The server-owned discovery-area catalog and its startup validation. */
class AreaCatalogLoaderTest {

    private static AreaPresets parse(String yaml) {
        return AreaCatalogLoader.parse(yaml.getBytes(StandardCharsets.UTF_8));
    }

    private static String one(String area) {
        return "minimumRestaurants: 15\nareas:\n" + area;
    }

    private static final String VALID =
        "  - {key: quan-1, label: Quận 1, order: 10, center: {lat: 10.7737, lng: 106.6999}, radiusMeters: 2000}\n";

    @Test
    void realCatalog_hasTheTenSpecifiedAreas_inDisplayOrder() {
        AreaPresets areas = AreaCatalogLoader.loadFromClasspath();
        assertThat(areas.all()).extracting(AreaPreset::key).containsExactly(
            "quan-1", "quan-3", "quan-5", "quan-7", "quan-10", "binh-thanh", "phu-nhuan", "go-vap", "tan-binh", "thu-duc");
        assertThat(areas.all()).extracting(AreaPreset::label).containsExactly(
            "Quận 1", "Quận 3", "Quận 5", "Quận 7", "Quận 10", "Bình Thạnh", "Phú Nhuận", "Gò Vấp", "Tân Bình",
            "Thủ Đức (An Phú – Thảo Điền)");
        assertThat(areas.minimumRestaurants()).isEqualTo(15);
        assertThat(areas.version()).hasSize(16);

        AreaPreset binhThanh = areas.get("binh-thanh").orElseThrow();
        assertThat(binhThanh.centerLat()).isEqualTo(10.8024);
        assertThat(binhThanh.centerLng()).isEqualTo(106.7136);
        assertThat(binhThanh.radiusMeters()).isEqualTo(2500);
        assertThat(areas.get("thu-duc").orElseThrow().radiusMeters()).isEqualTo(4000);
        assertThat(binhThanh.toString()).doesNotContain("10.8024", "106.7136");
    }

    @Test
    void sortedByOrder_notFilePosition() {
        AreaPresets areas = parse(one(
            "  - {key: b, label: B, order: 20, center: {lat: 10.8, lng: 106.7}, radiusMeters: 1000}\n" + VALID));
        assertThat(areas.all()).extracting(AreaPreset::key).containsExactly("quan-1", "b");
    }

    @ParameterizedTest(name = "{1}")
    @CsvSource(delimiter = '|', value = {
        "  - {key: quan-1, label: Khác, order: 20, center: {lat: 10.8, lng: 106.7}, radiusMeters: 1000}|duplicate key",
        "  - {key: q1b, label: Quận 1, order: 20, center: {lat: 10.8, lng: 106.7}, radiusMeters: 1000}|already used by 'quan-1'",
        "  - {key: q1b, label: Khác, order: 10, center: {lat: 10.8, lng: 106.7}, radiusMeters: 1000}|order 10 is already used",
    })
    void duplicates_failStartup(String second, String message) {
        // @CsvSource trims leading spaces: restore the list indentation
        assertThatThrownBy(() -> parse(one(VALID + "  " + second.strip() + "\n")))
            .isInstanceOf(InvalidAreaCatalogException.class).hasMessageContaining(message);
    }

    @ParameterizedTest(name = "{1}")
    @CsvSource(delimiter = '|', value = {
        "  - {key: Quan 1, label: Q, order: 1, center: {lat: 10.8, lng: 106.7}, radiusMeters: 1000}|lowercase slug",
        "  - {key: q, label: \" \", order: 1, center: {lat: 10.8, lng: 106.7}, radiusMeters: 1000}|'label' is required",
        "  - {key: q, label: Q, order: 0, center: {lat: 10.8, lng: 106.7}, radiusMeters: 1000}|order must be a positive integer",
        "  - {key: q, label: Q, center: {lat: 10.8, lng: 106.7}, radiusMeters: 1000}|order must be a positive integer",
        "  - {key: q, label: Q, order: 1, center: {lat: 91, lng: 106.7}, radiusMeters: 1000}|center.lat",
        "  - {key: q, label: Q, order: 1, center: {lat: 10.8, lng: x}, radiusMeters: 1000}|center.lng",
        "  - {key: q, label: Q, order: 1, center: {lat: 10.8}, radiusMeters: 1000}|center.lng",
        "  - {key: q, label: Q, order: 1, radiusMeters: 1000}|center must be",
        "  - {key: q, label: Q, order: 1, center: {lat: 10.8, lng: 106.7}, radiusMeters: 5001}|radiusMeters must be",
        "  - {key: q, label: Q, order: 1, center: {lat: 10.8, lng: 106.7}, radiusMeters: 50}|radiusMeters must be",
        "  - {key: q, label: Q, order: 1, center: {lat: 10.8, lng: 106.7}, radiusMeters: 1500.5}|radiusMeters must be",
        "  - {key: q, label: Q, order: 1, center: {lat: 10.8, lng: 106.7}, radiusMeters: 1000, district: true}|unknown field",
    })
    void invalidArea_failsStartup_namingTheArea(String area, String message) {
        assertThatThrownBy(() -> parse(one(area)))
            .isInstanceOf(InvalidAreaCatalogException.class)
            .hasMessageContaining(AreaCatalogLoader.RESOURCE)
            .hasMessageContaining(message);
    }

    @Test
    void invalidTopLevel_failsStartup() {
        assertThatThrownBy(() -> parse("areas:\n" + VALID)).hasMessageContaining("minimumRestaurants");
        assertThatThrownBy(() -> parse("minimumRestaurants: 0\nareas:\n" + VALID)).hasMessageContaining("minimumRestaurants");
        assertThatThrownBy(() -> parse("minimumRestaurants: 15\nareas: []\n")).hasMessageContaining("non-empty");
        assertThatThrownBy(() -> parse("minimumRestaurants: 15\nzones: []\nareas:\n" + VALID)).hasMessageContaining("unknown top-level");
        assertThatThrownBy(() -> parse("::: not yaml")).isInstanceOf(InvalidAreaCatalogException.class);
    }
}
