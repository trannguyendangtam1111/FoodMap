package com.foodmap.catalog;

import com.foodmap.restaurant.dto.AreaSupplyRow;
import com.foodmap.restaurant.repository.RestaurantRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Catalog and food-discovery endpoints through the real application context
 * (test profile, H2). Anonymous access, response privacy and input
 * validation; the PostGIS-backed counts are covered by
 * FoodAvailabilityServiceTest and by runtime checks on PostgreSQL. The area
 * supply query is mocked: every area has 40 restaurants except tan-binh (14).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@ActiveProfiles("test")
class CatalogApiIntegrationTest {

    @Autowired MockMvc mockMvc;
    @Autowired FoodVocabulary vocabulary;
    @MockBean RestaurantRepository restaurantRepository;

    @BeforeEach
    void setUp() {
        when(restaurantRepository.countRestaurantsWithinAreas(anyString(), anyString(), anyString())).thenAnswer(inv -> {
            List<AreaSupplyRow> rows = new ArrayList<>();
            for (long i = 1; i <= 10; i++) {
                long index = i, count = i == 9 ? 14 : 40;         // 9th area in display order: tan-binh
                rows.add(new AreaSupplyRow() {
                    public Long getAreaIndex() { return index; }
                    public Long getRestaurantCount() { return count; }
                });
            }
            return rows;
        });
    }

    @Test
    void catalog_isPublic_andExposesOnlyPublicFields() throws Exception {
        MvcResult res = mockMvc.perform(get("/api/v1/catalog"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.version").value(vocabulary.version()))
            .andExpect(jsonPath("$.data.foods.length()").value(36))
            .andExpect(jsonPath("$.data.foods[0].key").value("ca-phe"))
            .andExpect(jsonPath("$.data.foods[0].label").value("Cà phê"))
            .andExpect(jsonPath("$.data.foods[0].kind").value("DRINK"))
            .andExpect(jsonPath("$.data.foods[0].parentCategory").value("coffee"))
            .andExpect(jsonPath("$.data.foods[0].tone").value("taro"))
            .andExpect(jsonPath("$.data.foods[0].discoverable").value(true))
            .andExpect(jsonPath("$.data.foods[0].order").value(10))
            .andExpect(jsonPath("$.data.foods[0].artKey").value("ca-phe"))
            .andExpect(jsonPath("$.data.categories.length()").value(12))
            .andExpect(jsonPath("$.data.areas.length()").value(9))           // tan-binh: too few restaurants
            .andExpect(jsonPath("$.data.areas[0].key").value("quan-1"))
            .andExpect(jsonPath("$.data.areas[0].label").value("Quận 1"))
            .andExpect(jsonPath("$.data.areas[0].order").value(10))
            .andExpect(jsonPath("$.data.areas[8].key").value("thu-duc"))
            .andExpect(jsonPath("$.data.areas[?(@.key == 'tan-binh')]").isEmpty())
            .andExpect(header().string(HttpHeaders.ETAG, org.hamcrest.Matchers.startsWith("\"" + vocabulary.version() + "-")))
            .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "max-age=3600, public"))
            .andReturn();

        String body = res.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(body).doesNotContain("keywords", "suppressors", "asciiSafe", "restaurants\"",
            "labelTerm", "tokens", "index");
        // no keyword text leaks: "café" and "milk tea" are keywords, never labels
        assertThat(body).doesNotContain("café", "milk tea", "nhà hàng");
        // areas: key, label and order only — never a centre or a radius
        assertThat(body).doesNotContain("center", "radius", "\"lat\"", "\"lng\"", "10.7737", "106.6999", "2500");
    }

    @Test
    void catalog_conditionalGet_returns304() throws Exception {
        String etag = mockMvc.perform(get("/api/v1/catalog")).andReturn().getResponse().getHeader(HttpHeaders.ETAG);
        mockMvc.perform(get("/api/v1/catalog").header(HttpHeaders.IF_NONE_MATCH, etag))
            .andExpect(status().isNotModified());
        mockMvc.perform(get("/api/v1/catalog").header(HttpHeaders.IF_NONE_MATCH, "\"" + vocabulary.version() + "\""))
            .andExpect(status().isOk());                                    // the food version alone is not the ETag
        mockMvc.perform(get("/api/v1/catalog").header(HttpHeaders.IF_NONE_MATCH, "\"stale\""))
            .andExpect(status().isOk());
    }

    @Test
    void match_resolvesOnTheServer() throws Exception {
        mockMvc.perform(get("/api/v1/catalog/foods/match").param("q", "pho"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.foods.length()").value(1))
            .andExpect(jsonPath("$.data.foods[0].key").value("pho"))
            .andExpect(jsonPath("$.data.foods[0].label").value("Phở"));
        mockMvc.perform(get("/api/v1/catalog/foods/match").param("q", "phố"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.foods.length()").value(0));
    }

    @Test
    void match_intent_isTheFoodTheWholeQueryNames_neverAGuess() throws Exception {
        // the whole query names one discoverable food — accented, ASCII or multi-word
        String[][] named = {{"phở", "pho"}, {"pho", "pho"}, {"PHỞ", "pho"}, {"bún bò", "bun-bo"}, {"bun bo", "bun-bo"},
                            {"trà sữa", "tra-sua"}, {"sushi", "sushi"}, {"cafe", "ca-phe"}};
        for (String[] n : named) {
            mockMvc.perform(get("/api/v1/catalog/foods/match").param("q", n[0]))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.intent.key").value(n[1]))
                .andExpect(jsonPath("$.data.foods[0].key").value(n[1]));
        }
        mockMvc.perform(get("/api/v1/catalog/foods/match").param("q", "phở"))
            .andExpect(jsonPath("$.data.intent.label").value("Phở"));
        // a restaurant name mentions phở (a suggestion) but names no food (no intent)
        mockMvc.perform(get("/api/v1/catalog/foods/match").param("q", "Phở Hòa Pasteur"))
            .andExpect(jsonPath("$.data.foods[0].key").value("pho"))
            .andExpect(jsonPath("$.data.intent").doesNotExist());
        // unknown, street-name homograph, partial, longer, classification-only: no intent, nothing invented
        for (String q : new String[]{"xyz", "phố", "ph", "phở bò", "trà", "Pizza 4P's"}) {
            mockMvc.perform(get("/api/v1/catalog/foods/match").param("q", q))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.intent").doesNotExist());
        }
    }

    @Test
    void match_validatesInput() throws Exception {
        mockMvc.perform(get("/api/v1/catalog/foods/match")).andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/v1/catalog/foods/match").param("q", "   ")).andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/v1/catalog/foods/match").param("q", "a".repeat(101)))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.message").value("q must be at most 100 characters."));
    }

    @Test
    void availableFoods_isPublic_andRejectsMalformedScopes() throws Exception {
        for (String param : new String[]{"lat", "lng", "radius", "area"}) {
            mockMvc.perform(get("/api/v1/decisions/foods/available").param(param, "1"))
                .andExpect(status().isBadRequest());
        }
        mockMvc.perform(get("/api/v1/decisions/foods/available").param("kinds", "SNACK"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.message").value("kinds accepts MEAL, DRINK and DESSERT."));
    }
}
