package com.foodmap.integration.goong;

import com.foodmap.common.exception.GoongIntegrationException;
import com.foodmap.common.exception.GoongRunAbortedException;
import com.foodmap.common.security.SecretRedactor;
import com.foodmap.config.properties.GoongProperties;
import com.foodmap.integration.goong.dto.GoongAutocompleteResponse;
import com.foodmap.integration.goong.dto.GoongPlaceDTO;
import com.foodmap.integration.goong.dto.GoongPlaceDetailResponse;
import com.foodmap.integration.goong.dto.GoongPrediction;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;

@Slf4j
@Component
@RequiredArgsConstructor
public class GoongClientImpl implements GoongClient {

    /** Hard floor on backoff so we never instant-retry, even on misconfig. */
    private static final long MIN_BACKOFF_MS = 500L;

    /** During a snapshot run (budget armed): one try + at most one retry. */
    static final int SNAPSHOT_MAX_ATTEMPTS = 2;

    @Qualifier("goongRestTemplate")
    private final RestTemplate restTemplate;

    private final GoongProperties goongProperties;
    private final GoongRateLimiter rateLimiter;
    private final GoongHealthGate healthGate;
    /** Charged before every HTTP attempt; deny-by-default when no snapshot run armed it. */
    private final GoongRequestBudget requestBudget;

    @Override
    public List<GoongPrediction> autoComplete(String input, double lat, double lng, int radiusMeters) {
        int radiusKm = toGoongRadiusKm(radiusMeters);
        URI uri = UriComponentsBuilder.fromHttpUrl(goongProperties.getBaseUrl() + "/place/autocomplete")
            .queryParam("input",    input)
            .queryParam("location", lat + "," + lng)
            .queryParam("radius",   radiusKm)
            .queryParam("api_key",  goongProperties.getApiKey())
            .build()
            .encode()
            .toUri();

        GoongAutocompleteResponse body = callWithRetry(() -> {
            rateLimiter.acquire();
            log.debug("goong.autoComplete input='{}' lat={} lng={} radius_m={} radius_km={}",
                input, lat, lng, radiusMeters, radiusKm);
            ResponseEntity<GoongAutocompleteResponse> resp =
                restTemplate.getForEntity(uri, GoongAutocompleteResponse.class);
            return resp.getBody();
        }, "autoComplete");

        if (body == null) {
            return Collections.emptyList();
        }
        if (!body.isSuccess()) {
            log.warn("goong.autoComplete returned status={}", body.status());
        }
        return body.predictions() == null ? Collections.emptyList() : body.predictions();
    }

    /**
     * Goong AutoComplete takes `radius` in kilometres; the domain works in
     * metres. Rounded up to whole kilometres because fractional values are
     * not documented as accepted — e.g. 500 m is sent as 1 km.
     */
    private static int toGoongRadiusKm(int radiusMeters) {
        return (int) Math.ceil(radiusMeters / 1000.0);
    }

    @Override
    public Optional<GoongPlaceDTO> placeDetail(String goongPlaceId) {
        URI uri = UriComponentsBuilder.fromHttpUrl(goongProperties.getBaseUrl() + "/place/detail")
            .queryParam("place_id", goongPlaceId)
            .queryParam("api_key",  goongProperties.getApiKey())
            .build()
            .toUri();

        GoongPlaceDetailResponse body = callWithRetry(() -> {
            rateLimiter.acquire();
            log.debug("goong.placeDetail place_id={}", goongPlaceId);
            ResponseEntity<GoongPlaceDetailResponse> resp =
                restTemplate.getForEntity(uri, GoongPlaceDetailResponse.class);
            return resp.getBody();
        }, "placeDetail");

        if (body == null || !body.isSuccess()) {
            return Optional.empty();
        }
        return Optional.of(body.result());
    }

    // ── Retry with exponential backoff + jitter ───────────────────────────
    //
    // Behaviour:
    //   - 429 / 5xx / I/O are transient → retried up to maxRetryAttempts.
    //   - Backoff schedule is configured (default 2s, 5s, 15s) with ±25%
    //     random jitter so concurrent workers don't synchronise.
    //   - Final attempt does NOT sleep afterwards — we either return or
    //     throw immediately. No wasted backoff.
    //   - 4xx (other than 429) is a caller error and is NOT retried; 404
    //     resolves to null.
    //   - Every HTTP attempt (first try and each retry) is charged to the
    //     GoongRequestBudget first. Unarmed (outside a snapshot run) the
    //     charge throws: Goong is denied by default and no request is made.
    //   - Snapshot run (budget armed): at most one retry; a 429 or a
    //     DEGRADED provider stops the run (GoongRunAbortedException).

    private <T> T callWithRetry(Supplier<T> call, String opName) {
        boolean snapshotRun = requestBudget.isArmed();

        // Short-circuit when the provider is in DEGRADED mode — don't burn
        // retry budget against a sick provider. Worker treats this like
        // any other transient failure and the cell goes into cooldown.
        if (healthGate.shouldSkip()) {
            log.debug("goong.{} skipped — provider DEGRADED", opName);
            if (snapshotRun) {
                throw new GoongRunAbortedException(GoongRunAbortedException.Reason.PROVIDER_DEGRADED,
                    "Goong " + opName + " skipped: provider degraded", null);
            }
            throw new GoongIntegrationException(
                "Goong " + opName + " skipped: provider degraded");
        }

        int maxAttempts = Math.max(1, goongProperties.getMaxRetryAttempts());
        if (snapshotRun) maxAttempts = Math.min(maxAttempts, SNAPSHOT_MAX_ATTEMPTS);

        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            // Before the HTTP attempt: throws (non-retryable, costs nothing) when not allowed.
            requestBudget.charge(opName, attempt);

            String failureKind = null;
            Exception failure  = null;
            try {
                T result = call.get();
                healthGate.observeSuccess();
                return result;
            } catch (HttpClientErrorException.TooManyRequests e) {
                failureKind = "429";
                failure = e;
            } catch (HttpServerErrorException e) {
                failureKind = "5xx:" + e.getStatusCode().value();
                failure = e;
            } catch (ResourceAccessException e) {
                failureKind = "io";
                failure = e;
            } catch (HttpClientErrorException e) {
                if (e.getStatusCode() == HttpStatus.NOT_FOUND) {
                    log.debug("goong.{} returned 404", opName);
                    return null;
                }
                requestBudget.recordFailure("4xx:" + e.getStatusCode().value());
                throw new GoongIntegrationException(
                    "Goong " + opName + " failed: " + e.getStatusCode(), SecretRedactor.sanitize(e));
            }

            // The HTTP client's exception may quote the request URL (with api_key): only a
            // redacted copy travels on as a cause (into logs, reports, stack traces).
            failure = SecretRedactor.sanitize(failure);

            // Transient failure → feed the health gate.
            healthGate.observeFailure(failureKind);
            requestBudget.recordFailure(failureKind);

            // Snapshot run: a 429 stops the run — never spend the budget retrying into it.
            if (snapshotRun && "429".equals(failureKind)) {
                log.warn("goong.{} returned 429 during a snapshot run — stopping", opName);
                throw new GoongRunAbortedException(GoongRunAbortedException.Reason.RATE_LIMITED,
                    "Goong " + opName + " rate-limited (429)", failure);
            }

            if (attempt == maxAttempts) {
                log.warn("goong.{} exhausted {} attempts ({})", opName, maxAttempts, failureKind);
                throw new GoongIntegrationException(
                    "Goong " + opName + " failed after " + maxAttempts + " attempts: " + failureKind,
                    failure);
            }

            // If the gate just flipped DEGRADED, stop spending retries.
            if (healthGate.shouldSkip()) {
                log.warn("goong.{} aborting retries — provider DEGRADED after {}/{}",
                    opName, attempt, maxAttempts);
                if (snapshotRun) {
                    throw new GoongRunAbortedException(GoongRunAbortedException.Reason.PROVIDER_DEGRADED,
                        "Goong " + opName + " degraded mid-retry: " + failureKind, failure);
                }
                throw new GoongIntegrationException(
                    "Goong " + opName + " degraded mid-retry: " + failureKind, failure);
            }

            long sleepMs = backoffForRetry(attempt);
            log.warn("goong.{} attempt {}/{} failed ({}) — retrying in {} ms",
                opName, attempt, maxAttempts, failureKind, sleepMs);
            sleep(sleepMs);
        }
        // Unreachable — loop either returns, throws, or completes the for.
        throw new GoongIntegrationException("Goong " + opName + " exhausted retries");
    }

    /**
     * Backoff for retry number N (1-based). Picks the configured base from
     * {@code retryBackoffMs[N-1]} (last value reused for higher N), clamps to
     * the global ceiling, then applies ±25% uniform jitter.
     */
    private long backoffForRetry(int retryNumber) {
        List<Long> schedule = goongProperties.getRetryBackoffMs();
        long base;
        if (schedule == null || schedule.isEmpty()) {
            base = 2_000L * (long) Math.pow(2, retryNumber - 1);
        } else {
            int idx = Math.min(retryNumber - 1, schedule.size() - 1);
            base = schedule.get(idx);
        }
        base = Math.max(MIN_BACKOFF_MS, Math.min(base, goongProperties.getRetryMaxBackoffMs()));

        // ±25% jitter — uniformly distributed, full jitter would be too aggressive
        // (some workers retry too quickly during a 429 storm).
        double jitter = (ThreadLocalRandom.current().nextDouble() * 0.5) - 0.25;
        long withJitter = (long) (base * (1.0 + jitter));
        return Math.max(MIN_BACKOFF_MS, withJitter);
    }

    private void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new GoongIntegrationException("Interrupted during Goong retry backoff", e);
        }
    }
}
