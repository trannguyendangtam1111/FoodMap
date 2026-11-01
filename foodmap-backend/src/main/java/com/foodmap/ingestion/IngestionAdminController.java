package com.foodmap.ingestion;

import com.foodmap.common.dto.ApiResponse;
import com.foodmap.integration.goong.GoongHealthGate;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Read-only operational endpoint for the ingestion pipeline.
 *
 *   GET /internal/ingestion/health   (ADMIN)
 *
 * Returns a single snapshot of:
 *   - queue depth + in-flight count
 *   - cumulative crawl outcome counters
 *   - Goong provider health gate (HEALTHY / DEGRADED + cooldown end)
 *
 * Useful for:
 *   - operators verifying that workers are draining
 *   - smoke-testing the 429 → DEGRADED → recovery loop
 *   - interview demos of "what does production look like?"
 *
 * Kept on its own /internal prefix so it is obvious this is NOT a public
 * route. ADMIN-gated via {@link PreAuthorize} — also covered by the
 * SecurityConfig catch-all "anyRequest authenticated" rule.
 */
@RestController
@RequestMapping("/internal/ingestion")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
@Tag(name = "Ingestion (admin)", description = "Operational view of the background ingestion pipeline")
public class IngestionAdminController {

    private final IngestionQueue queue;
    private final IngestionMetrics metrics;
    private final GoongHealthGate goongHealthGate;

    @GetMapping("/health")
    @Operation(summary = "Snapshot of ingestion queue, metrics and provider health")
    public ResponseEntity<ApiResponse<HealthSnapshot>> health() {
        HealthSnapshot snapshot = new HealthSnapshot(
            new QueueState(queue.queuedSize(), queue.inFlightSize()),
            metrics.snapshot(),
            goongHealthGate.snapshot()
        );
        return ResponseEntity.ok(ApiResponse.ok(snapshot));
    }

    public record QueueState(int queued, int inFlight) {}

    public record HealthSnapshot(
        QueueState queue,
        IngestionMetrics.Snapshot metrics,
        GoongHealthGate.Snapshot provider
    ) {}
}
