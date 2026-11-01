package com.foodmap.integration.goong;

import com.foodmap.common.exception.BudgetExhaustedException;
import com.foodmap.common.exception.GoongIntegrationException;
import com.foodmap.common.exception.GoongRunAbortedException;
import com.foodmap.config.properties.GoongProperties;
import com.foodmap.integration.goong.dto.GoongPrediction;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.ExpectedCount;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.hamcrest.Matchers.startsWith;

/**
 * GoongClientImpl charges the GoongRequestBudget before EVERY HTTP attempt.
 * A mocked HTTP server only — no real Goong request.
 */
class GoongClientImplBudgetTest {

    private static final String BASE_URL = "https://goong.test";
    private static final String AUTOCOMPLETE = BASE_URL + "/place/autocomplete";
    private static final String ONE_PREDICTION = """
        {"status":"OK","predictions":[{"place_id":"p1","description":"Phở Hòa, 260C Pasteur",
          "structured_formatting":{"main_text":"Phở Hòa","secondary_text":"260C Pasteur, Quận 3"}}]}
        """;

    private MockRestServiceServer server;
    private GoongClientImpl client;
    private GoongRequestBudget budget;
    private GoongProperties properties;

    @BeforeEach
    void setUp() {
        properties = new GoongProperties();
        properties.setBaseUrl(BASE_URL);
        properties.setApiKey("test-key");
        properties.setMaxRetryAttempts(3);              // the normal default
        properties.setRetryBackoffMs(List.of(1L));      // clamped to the 500 ms floor
        RestTemplate restTemplate = new RestTemplate();
        server = MockRestServiceServer.bindTo(restTemplate).build();
        budget = new GoongRequestBudget();
        client = new GoongClientImpl(restTemplate, properties, mock(GoongRateLimiter.class), new GoongHealthGate(), budget);
    }

    @Test
    void withoutAnArmedBudgetNoHttpRequestIsMade() {
        assertThatThrownBy(() -> client.autoComplete("restaurant", 10.77, 106.70, 500))
            .isInstanceOf(BudgetExhaustedException.class);
        assertThatThrownBy(() -> client.placeDetail("p1")).isInstanceOf(BudgetExhaustedException.class);
        server.verify();   // no request expected, none made
    }

    @Test
    void aRetryConsumesTheBudget() {
        budget.arm(5);
        server.expect(ExpectedCount.once(), requestTo(startsWith(AUTOCOMPLETE))).andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));
        server.expect(ExpectedCount.once(), requestTo(startsWith(AUTOCOMPLETE)))
            .andRespond(withSuccess(ONE_PREDICTION, MediaType.APPLICATION_JSON));

        List<GoongPrediction> predictions = client.autoComplete("restaurant", 10.77, 106.70, 500);

        server.verify();
        assertThat(budget.used()).isEqualTo(2);
        assertThat(budget.snapshot().retries()).isEqualTo(1);
        assertThat(budget.snapshot().failures()).containsEntry("5xx:503", 1);
        assertThat(predictions).singleElement().satisfies(p -> {
            assertThat(p.placeId()).isEqualTo("p1");
            assertThat(p.mainText()).isEqualTo("Phở Hòa");
            assertThat(p.secondaryText()).isEqualTo("260C Pasteur, Quận 3");
        });
    }

    @Test
    void inASnapshotRunAtMostOneRetryEvenIfThreeAreConfigured() {
        budget.arm(10);
        server.expect(ExpectedCount.times(2), requestTo(startsWith(AUTOCOMPLETE))).andRespond(withStatus(HttpStatus.BAD_GATEWAY));

        assertThatThrownBy(() -> client.autoComplete("restaurant", 10.77, 106.70, 500))
            .isInstanceOf(GoongIntegrationException.class)
            .hasMessageContaining("after 2 attempts");
        server.verify();
        assertThat(budget.used()).isEqualTo(2);
    }

    @Test
    void a429StopsTheRunWithoutRetrying() {
        budget.arm(10);
        server.expect(ExpectedCount.once(), requestTo(startsWith(AUTOCOMPLETE))).andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS));

        assertThatThrownBy(() -> client.autoComplete("restaurant", 10.77, 106.70, 500))
            .isInstanceOf(GoongRunAbortedException.class)
            .satisfies(e -> assertThat(((GoongRunAbortedException) e).getReason())
                .isEqualTo(GoongRunAbortedException.Reason.RATE_LIMITED));
        server.verify();
        assertThat(budget.used()).isEqualTo(1);
    }

    @Test
    void theBudgetStopsARetryBeforeItIsSent() {
        budget.arm(1);
        server.expect(ExpectedCount.once(), requestTo(startsWith(AUTOCOMPLETE))).andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));

        assertThatThrownBy(() -> client.autoComplete("restaurant", 10.77, 106.70, 500))
            .isInstanceOf(BudgetExhaustedException.class);
        server.verify();   // exactly one request: the retry was never sent
        assertThat(budget.used()).isEqualTo(1);
    }
}
