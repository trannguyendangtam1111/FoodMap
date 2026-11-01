package com.foodmap.integration.goong;

import com.foodmap.config.properties.GoongProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.ExpectedCount;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.hamcrest.Matchers.startsWith;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;

/**
 * Whatever the HTTP client puts in its exception (the request URL with
 * api_key, an echoed response body), the exceptions GoongClientImpl throws —
 * messages, causes, printed stack traces — carry no key. Mocked HTTP only.
 */
class GoongClientImplRedactionTest {

    static final String BASE_URL = "https://goong.test";
    static final String KEY = "GoongKey-0123456789abcdef";

    RestTemplate restTemplate;
    MockRestServiceServer server;
    GoongClientImpl client;

    @BeforeEach
    void setUp() {
        GoongProperties properties = new GoongProperties();
        properties.setBaseUrl(BASE_URL);
        properties.setApiKey(KEY);
        properties.setRetryBackoffMs(List.of(1L));
        restTemplate = new RestTemplate();
        server = MockRestServiceServer.bindTo(restTemplate).build();
        GoongRequestBudget budget = new GoongRequestBudget();
        budget.arm(10);
        client = new GoongClientImpl(restTemplate, properties, mock(GoongRateLimiter.class), new GoongHealthGate(), budget);
    }

    static String everything(Throwable t) {
        StringWriter out = new StringWriter();
        t.printStackTrace(new PrintWriter(out));
        return t + "\n" + out;
    }

    @Test
    void anIoErrorQuotingTheUrlNeverLeaksTheKey() {
        server.expect(ExpectedCount.manyTimes(), requestTo(startsWith(BASE_URL + "/place/detail")))
            .andRespond(withException(new IOException("Connection reset by "
                + BASE_URL + "/place/detail?place_id=p1&api_key=" + KEY)));

        Throwable thrown = catchThrowable(() -> client.placeDetail("p1"));

        assertThat(thrown).isNotNull();
        assertThat(everything(thrown)).doesNotContain(KEY).contains("api_key=***");
    }

    @Test
    void aServerErrorEchoingTheKeyNeverLeaksIt() {
        server.expect(ExpectedCount.manyTimes(), requestTo(startsWith(BASE_URL + "/place/autocomplete")))
            .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE).contentType(MediaType.TEXT_PLAIN)
                .body("upstream failed for ?api_key=" + KEY));

        Throwable thrown = catchThrowable(() -> client.autoComplete("Phở", 10.8, 106.7, 2000));

        assertThat(thrown).isNotNull();
        assertThat(everything(thrown)).doesNotContain(KEY);
    }

    @Test
    void aClientErrorEchoingTheKeyNeverLeaksIt() {
        server.expect(requestTo(startsWith(BASE_URL + "/place/detail")))
            .andRespond(withStatus(HttpStatus.FORBIDDEN).contentType(MediaType.APPLICATION_JSON)
                .body("{\"error\":\"invalid api_key\",\"api_key\":\"" + KEY + "\"}"));

        Throwable thrown = catchThrowable(() -> client.placeDetail("p1"));

        assertThat(thrown).hasMessageContaining("403");
        assertThat(everything(thrown)).doesNotContain(KEY);
    }

    @Test
    void aRateLimitAbortCarriesNoKey() {
        server.expect(requestTo(startsWith(BASE_URL + "/place/detail")))
            .andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS).body("slow down " + BASE_URL + "?api_key=" + KEY));

        Throwable thrown = catchThrowable(() -> client.placeDetail("p1"));

        assertThat(thrown).isInstanceOf(com.foodmap.common.exception.GoongRunAbortedException.class);
        assertThat(everything(thrown)).doesNotContain(KEY);
    }
}
