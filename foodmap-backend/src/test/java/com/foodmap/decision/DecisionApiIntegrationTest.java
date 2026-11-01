package com.foodmap.decision;

import com.foodmap.ingestion.IngestionQueue;
import com.foodmap.integration.goong.GoongClient;
import com.foodmap.restaurant.domain.Restaurant;
import com.foodmap.restaurant.repository.RestaurantFoodRepository;
import com.foodmap.restaurant.repository.RestaurantRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * GET /api/v1/decisions/today through the full Spring stack: security,
 * validation, response envelope, rate limiting and the read-only guarantee.
 *
 * The restaurant repository is mocked because its PostGIS radius query can't
 * run on the H2 test database; everything else is the real application.
 * Each test uses its own client IP so the shared rate limiter can't couple them.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@ActiveProfiles("test")
class DecisionApiIntegrationTest {

    private static final String URL = "/api/v1/decisions/today";
    private static final AtomicInteger NEXT_IP = new AtomicInteger(1);

    @Autowired MockMvc mockMvc;
    @MockBean RestaurantRepository restaurantRepository;
    @MockBean RestaurantFoodRepository restaurantFoodRepository;
    @MockBean GoongClient goongClient;
    @SpyBean IngestionQueue ingestionQueue;

    @BeforeEach
    void setUp() {
        Restaurant pho = Restaurant.builder()
            .id(1L).goongPlaceId("g-1").name("Phở Hòa Pasteur")
            .latitude(10.7790).longitude(106.7009).isActive(true).build();
        when(restaurantRepository.findDecisionCandidates(anyDouble(), anyDouble(), anyDouble(), anyDouble(), anyDouble(), anyDouble(), anyDouble(), anyInt()))
            .thenReturn(List.of(pho));
        when(restaurantFoodRepository.findDishNamesByRestaurantIds(anyCollection())).thenReturn(List.of());
    }

    /** A request from a fresh client IP. */
    private static MockHttpServletRequestBuilder fresh(MockHttpServletRequestBuilder b) {
        String ip = "10.0.0." + NEXT_IP.getAndIncrement();
        return b.with(r -> { r.setRemoteAddr(ip); return r; });
    }

    @Test
    void anonymousRequest_returnsDecision() throws Exception {
        mockMvc.perform(fresh(get(URL).param("lat", "10.7769").param("lng", "106.7009")
                .param("mood", "mon-nuoc")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.data.decisionId").isNotEmpty())
            .andExpect(jsonPath("$.data.restaurant.id").value(1))
            .andExpect(jsonPath("$.data.restaurant.name").value("Phở Hòa Pasteur"))
            .andExpect(jsonPath("$.data.candidateCount").value(1))
            .andExpect(jsonPath("$.data.fallbackLevel").value(0))
            .andExpect(jsonPath("$.data.locationSource").value("USER"))
            .andExpect(jsonPath("$.data.moodMatched").value(true))
            .andExpect(jsonPath("$.data.reasons[0].code").value("DISTANCE"))
            .andExpect(jsonPath("$.data.reasons[1].label").value("Phù hợp món nước"));
    }

    @Test
    void noLocation_isAllowed_andMarkedAsCityCentre() throws Exception {
        mockMvc.perform(fresh(get(URL)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.fallbackLevel").value(3))
            .andExpect(jsonPath("$.data.locationSource").value("CITY_CENTER"))
            .andExpect(jsonPath("$.data.distanceMeters").doesNotExist());
    }

    @Test
    void noCandidates_is200_withNullRestaurant() throws Exception {
        when(restaurantRepository.findDecisionCandidates(anyDouble(), anyDouble(), anyDouble(), anyDouble(), anyDouble(), anyDouble(), anyDouble(), anyInt()))
            .thenReturn(List.of());
        mockMvc.perform(fresh(get(URL).param("lat", "10.7769").param("lng", "106.7009")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.restaurant").doesNotExist())
            .andExpect(jsonPath("$.data.decisionId").doesNotExist())
            .andExpect(jsonPath("$.data.candidateCount").value(0));
    }

    @Test
    void invalidParameters_are400_not500() throws Exception {
        String[][] bad = {
            {"lat", "91", "lng", "106"}, {"lat", "10", "lng", "200"}, {"lat", "10.7", "radius", "1000"},
            {"radius", "0"}, {"radius", "999999"}, {"radius", "abc"}, {"mood", "spicy"}, {"exclude", "1,x"},
        };
        for (String[] params : bad) {
            var request = get(URL);
            for (int i = 0; i < params.length; i += 2) request = request.param(params[i], params[i + 1]);
            mockMvc.perform(fresh(request))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.message").isNotEmpty());
        }
    }

    @Test
    void duplicateExcludeIds_areAccepted() throws Exception {
        mockMvc.perform(fresh(get(URL).param("exclude", "5,5,6")))
            .andExpect(status().isOk());
    }

    @Test
    void repeatedRequests_hitTheRateLimit() throws Exception {
        String ip = "10.9.9.9";
        int ok = 0;
        for (int i = 0; i < 20; i++) {
            int code = mockMvc.perform(get(URL).with(r -> { r.setRemoteAddr(ip); return r; }))
                .andReturn().getResponse().getStatus();
            if (code == 200) ok++;
        }
        assertThat(ok).isEqualTo(20);   // default burst is 20

        // Refill is 20/min (one token per 3 s); allow a couple of refills on a slow
        // machine, then the limiter must answer 429 with Retry-After.
        int code = 200;
        for (int i = 0; i < 5 && code == 200; i++) {
            code = mockMvc.perform(get(URL).with(r -> { r.setRemoteAddr(ip); return r; }))
                .andReturn().getResponse().getStatus();
        }
        assertThat(code).isEqualTo(429);
        mockMvc.perform(get(URL).with(r -> { r.setRemoteAddr(ip); return r; }))
            .andExpect(status().isTooManyRequests())
            .andExpect(header().exists("Retry-After"))
            .andExpect(jsonPath("$.status").value(429));

        // another client is unaffected
        mockMvc.perform(fresh(get(URL))).andExpect(status().isOk());
    }

    @Test
    void onlyTheExactPathIsPublic() throws Exception {
        mockMvc.perform(fresh(get("/api/v1/decisions/history")))
            .andExpect(status().isUnauthorized());
    }

    @Test
    void endpoint_neverEnqueuesIngestion_callsGoong_orWrites() throws Exception {
        mockMvc.perform(fresh(get(URL).param("lat", "10.7769").param("lng", "106.7009")
                .param("mood", "mon-nuoc").param("exclude", "99")))
            .andExpect(status().isOk());
        mockMvc.perform(fresh(get(URL))).andExpect(status().isOk());

        verify(ingestionQueue, never()).offer(any());
        verifyNoInteractions(goongClient);
        assertThat(mockingDetails(restaurantRepository).getInvocations())
            .allSatisfy(inv -> assertThat(inv.getMethod().getName()).startsWith("find"));
        assertThat(mockingDetails(restaurantFoodRepository).getInvocations())
            .allSatisfy(inv -> assertThat(inv.getMethod().getName()).startsWith("find"));
    }
}
