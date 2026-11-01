package com.foodmap.decision.service;

import com.foodmap.catalog.FoodCatalogLoader;
import com.foodmap.catalog.FoodEntry;
import com.foodmap.catalog.FoodKind;
import com.foodmap.catalog.FoodVocabulary;
import com.foodmap.common.exception.BadRequestException;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FoodDecisionRequestTest {

    private static final FoodVocabulary V = FoodCatalogLoader.loadFromClasspath();

    @Test
    void defaults_mealsOnly_nothingExcluded() {
        FoodDecisionRequest r = FoodDecisionRequest.parse(null, null, V);
        assertThat(r.kinds()).containsExactly(FoodKind.MEAL);
        assertThat(r.excludedFoods()).isEmpty();
        assertThat(FoodDecisionRequest.parse(" ", "", V).kinds()).containsExactly(FoodKind.MEAL);
    }

    @Test
    void kinds_anyCase_unknownIs400() {
        assertThat(FoodDecisionRequest.parse("drink, Dessert", null, V).kinds())
            .containsExactlyInAnyOrder(FoodKind.DRINK, FoodKind.DESSERT);
        assertThatThrownBy(() -> FoodDecisionRequest.parse("SNACK", null, V)).isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> FoodDecisionRequest.parse(",", null, V)).isInstanceOf(BadRequestException.class);
        assertThat(FoodDecisionRequest.parseKinds(null, EnumSet.allOf(FoodKind.class))).hasSize(3);
    }

    @Test
    void exclude_duplicatesCollapse_blanksAreTolerated() {
        assertThat(FoodDecisionRequest.parse(null, "pho, bun,,pho ,bun", V).excludedFoods()).containsExactly("pho", "bun");
    }

    @Test
    void exclude_unknownFoodIs400() {
        assertThatThrownBy(() -> FoodDecisionRequest.parse(null, "pho,phố", V))
            .isInstanceOf(BadRequestException.class).hasMessageContaining("unknown food 'phố'");
    }

    @Test
    void exclude_atMost30DistinctFoods() {
        String thirty = V.entries().stream().limit(30).map(FoodEntry::key).collect(Collectors.joining(","));
        assertThat(FoodDecisionRequest.parse(null, thirty, V).excludedFoods()).hasSize(30);
        // 30 distinct + duplicates is still 30
        assertThat(FoodDecisionRequest.parse(null, thirty + ",pho,pho", V).excludedFoods()).hasSize(30);
        String thirtyOne = V.entries().stream().limit(31).map(FoodEntry::key).collect(Collectors.joining(","));
        assertThatThrownBy(() -> FoodDecisionRequest.parse(null, thirtyOne, V))
            .isInstanceOf(BadRequestException.class).hasMessageContaining("at most 30");
    }

    @Test
    void food_mustBeADiscoverableCatalogKey() {
        assertThat(FoodDecisionRequest.parseFood(" pho ", V).key()).isEqualTo("pho");
        assertThatThrownBy(() -> FoodDecisionRequest.parseFood("phở", V)).isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> FoodDecisionRequest.parseFood("tra", V))       // classification-only
            .isInstanceOf(BadRequestException.class).hasMessageContaining("Unknown food 'tra'");
    }
}
