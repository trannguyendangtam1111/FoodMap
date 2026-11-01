package com.foodmap.integration.goong;

import com.foodmap.config.properties.GoongProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.springframework.http.HttpMethod.GET;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.queryParam;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.hamcrest.Matchers.startsWith;

/**
 * Goong AutoComplete takes `radius` in kilometres; the domain passes metres.
 * Verifies the outgoing request parameter against a mocked HTTP server —
 * no real Goong call is made.
 */
class GoongClientImplRadiusTest {

    private static final String BASE_URL = "https://goong.test";

    private RestTemplate restTemplate;
    private MockRestServiceServer server;
    private GoongClientImpl client;

    @BeforeEach
    void setUp() {
        GoongProperties properties = new GoongProperties();
        properties.setBaseUrl(BASE_URL);
        properties.setApiKey("test-key");

        restTemplate = new RestTemplate();
        server = MockRestServiceServer.bindTo(restTemplate).build();
        // Goong is deny-by-default: arm a budget for this mocked server (no real Goong call).
        GoongRequestBudget budget = new GoongRequestBudget();
        budget.arm(10);
        client = new GoongClientImpl(restTemplate, properties,
            mock(GoongRateLimiter.class), new GoongHealthGate(), budget);
    }

    @ParameterizedTest(name = "{0} m -> radius={1} km")
    @CsvSource({
        "500,  1",
        "1,    1",
        "999,  1",
        "1000, 1",
        "1001, 2",
    })
    void radiusIsSentInWholeKilometres(int radiusMeters, String expectedRadiusKm) {
        server.expect(requestTo(startsWith(BASE_URL + "/place/autocomplete")))
            .andExpect(method(GET))
            .andExpect(queryParam("radius", expectedRadiusKm))
            .andExpect(queryParam("input", "restaurant"))
            .andExpect(queryParam("location", "10.7769,106.7009"))
            .andExpect(request -> assertThat(request.getURI().getQuery()).doesNotContain("limit="))
            .andRespond(withSuccess(
                "{\"status\":\"OK\",\"predictions\":[{\"place_id\":\"p1\",\"description\":\"d\"}]}",
                MediaType.APPLICATION_JSON));

        var predictions = client.autoComplete("restaurant", 10.7769, 106.7009, radiusMeters);

        server.verify();
        assertThat(predictions).hasSize(1);
    }
}
