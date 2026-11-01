package com.foodmap.decision;

import com.foodmap.common.exception.BadRequestException;
import com.foodmap.config.properties.DecisionProperties;
import com.foodmap.decision.scope.LocationScope;
import com.foodmap.decision.service.DecisionRequest;
import com.foodmap.decision.service.Mood;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Validation of the non-scope GET /api/v1/decisions/today parameters (mood,
 * exclude). lat/lng/radius/area are LocationScopeParser's: see
 * LocationScopeParserTest.
 */
class DecisionRequestTest {

    private final DecisionProperties props = new DecisionProperties();
    private final LocationScope centre = LocationScope.cityCentre(props);

    private DecisionRequest parse(String mood, String exclude) {
        return DecisionRequest.parse(centre, mood, exclude, props);
    }

    @Test
    void noParameters_isValid_withDefaults() {
        DecisionRequest r = parse(null, null);
        assertThat(r.scope()).isSameAs(centre);
        assertThat(r.mood()).isNull();
        assertThat(r.excludeIds()).isEmpty();
    }

    @Test
    void fullValidRequest_isParsed() {
        LocationScope user = LocationScope.user(10.7769, 106.7009, 800, props);
        DecisionRequest r = DecisionRequest.parse(user, "mon-nuoc", "12,40", props);
        assertThat(r.scope()).isSameAs(user);
        assertThat(r.mood()).isEqualTo(Mood.MON_NUOC);
        assertThat(r.excludeIds()).containsExactly(12L, 40L);
    }

    @Test
    void unsupportedMood_isRejected_withSupportedList() {
        assertThatThrownBy(() -> parse("spicy", null))
            .isInstanceOf(BadRequestException.class)
            .hasMessageContaining("Unsupported mood 'spicy'")
            .hasMessageContaining("mon-nuoc");
    }

    @Test
    void blankMood_meansNoPreference() {
        assertThat(parse("  ", null).mood()).isNull();
    }

    @ParameterizedTest(name = "exclude={0}")
    @CsvSource(delimiter = '|', value = {"12,abc", "12;40", "1.5", "-3", "0"})
    void malformedExclude_isRejected(String exclude) {
        assertThatThrownBy(() -> parse(null, exclude))
            .isInstanceOf(BadRequestException.class).hasMessageContaining("exclude");
    }

    @Test
    void duplicateAndEmptyExcludeTokens_areTolerated() {
        DecisionRequest r = parse(null, "12, 40,12,,40, ");
        assertThat(r.excludeIds()).containsExactly(12L, 40L);
    }

    @Test
    void tooManyExcludeIds_isRejected() {
        String many = String.join(",", java.util.stream.LongStream.rangeClosed(1, 51).mapToObj(Long::toString).toList());
        assertThatThrownBy(() -> parse(null, many))
            .isInstanceOf(BadRequestException.class).hasMessageContaining("at most 50");
    }
}
