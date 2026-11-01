package com.foodmap.config;

import com.foodmap.config.properties.GoongProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;

@Configuration
@RequiredArgsConstructor
public class GoongConfig {

    private final GoongProperties goongProperties;

    /**
     * RestTemplate dedicated to Goong outbound calls.
     *
     * Connect and read timeouts are set explicitly from {@link GoongProperties}.
     * Without them, SimpleClientHttpRequestFactory treats a null timeout as
     * "wait forever" — a hung Goong endpoint would then block an ingestion
     * worker thread indefinitely, defeating both the rate limiter (which
     * only guards *waiting for a permit*) and the health gate (which observes
     * transient failures, not hangs).
     */
    @Bean(name = "goongRestTemplate")
    public RestTemplate goongRestTemplate(RestTemplateBuilder builder) {
        return builder
            .rootUri(goongProperties.getBaseUrl())
            .setConnectTimeout(Duration.ofMillis(goongProperties.getConnectTimeoutMs()))
            .setReadTimeout(Duration.ofMillis(goongProperties.getReadTimeoutMs()))
            .build();
    }
}
