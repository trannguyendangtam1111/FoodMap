package com.foodmap.restaurant.service;

import com.foodmap.common.exception.GoongIntegrationException;
import com.foodmap.common.exception.ResourceNotFoundException;
import com.foodmap.config.properties.AppProperties;
import com.foodmap.integration.goong.GoongClient;
import com.foodmap.integration.goong.dto.GoongPlaceDTO;
import com.foodmap.integration.goong.mapper.GoongPlaceMapper;
import com.foodmap.restaurant.domain.Restaurant;
import com.foodmap.restaurant.repository.RestaurantRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Function;

/**
 * Bridges Goong place data into our local cache.
 *
 *   syncPlace        — force-fetch via Place Detail (admin sync / detail page).
 *   fetchIfUnknown   — snapshot-ingestion path: Place Detail only for a
 *                      place_id not stored yet; never refreshes; validates
 *                      before saving.
 *   ensureFreshPlace — discovery-pipeline path: skips Place Detail when the
 *                      row is already cached and within syncTtlDays.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RestaurantSyncService {

    private final GoongClient goongClient;
    private final GoongPlaceMapper mapper;
    private final RestaurantRepository restaurantRepository;
    private final AppProperties appProperties;

    /**
     * Force-sync a single restaurant by goong_place_id.
     */
    @Transactional
    public Restaurant syncPlace(String goongPlaceId) {
        GoongPlaceDTO dto = goongClient.placeDetail(goongPlaceId)
            .orElseThrow(() -> new ResourceNotFoundException(
                "Goong place not found: " + goongPlaceId));

        return upsert(dto);
    }

    /**
     * Outcome of ensureFreshPlace. Carries enough signal for the
     * IngestionWorker to count Place Detail API calls accurately
     * (distinguishing cache hits from actual Goong round-trips).
     */
    public record PlaceSyncResult(Optional<Restaurant> restaurant, boolean goongCalled) {
        public boolean isPresent() { return restaurant.isPresent(); }
        public static PlaceSyncResult cacheHit(Restaurant r) {
            return new PlaceSyncResult(Optional.of(r), false);
        }
        public static PlaceSyncResult fetched(Optional<Restaurant> r) {
            return new PlaceSyncResult(r, true);
        }
    }

    /**
     * Discovery-pipeline entry point. Called once per unique place_id
     * surfaced by KeywordFanoutService.
     *
     *   - already cached and fresh  → return existing row, NO Goong call
     *                                 (PlaceSyncResult.goongCalled == false)
     *   - missing or stale          → Place Detail fetch + upsert
     *   - Goong 404 or transport    → returns the cached copy (or empty)
     *                                 so the worker can move on; one bad
     *                                 id never fails a whole cell.
     */
    @Transactional
    public PlaceSyncResult ensureFreshPlace(String goongPlaceId) {
        Optional<Restaurant> cached = restaurantRepository.findByGoongPlaceId(goongPlaceId);
        if (cached.isPresent() && !isStale(cached.get())) {
            log.debug("Place {} already fresh — skipping Goong detail", goongPlaceId);
            return PlaceSyncResult.cacheHit(cached.get());
        }
        try {
            Optional<GoongPlaceDTO> dto = goongClient.placeDetail(goongPlaceId);
            if (dto.isEmpty()) {
                log.debug("Goong has no detail for place_id={}", goongPlaceId);
                return PlaceSyncResult.fetched(cached);
            }
            if (!isValidPlace(dto.get())) {
                log.debug("Place {} skipped — Goong returned incomplete payload", goongPlaceId);
                return PlaceSyncResult.fetched(cached);
            }
            return PlaceSyncResult.fetched(Optional.of(upsert(dto.get())));
        } catch (GoongIntegrationException e) {
            log.warn("Goong detail failed for place_id={} — keeping cached copy", goongPlaceId, e);
            return PlaceSyncResult.fetched(cached);
        }
    }

    public boolean isStale(Restaurant restaurant) {
        if (restaurant.getLastSyncedAt() == null) return true;
        Instant threshold = Instant.now().minus(
            appProperties.getSyncTtlDays(), ChronoUnit.DAYS);
        return restaurant.getLastSyncedAt().isBefore(threshold);
    }

    // ── Snapshot ingestion ────────────────────────────────

    public enum FetchStatus { KNOWN, NOT_FOUND, INVALID, REJECTED, CREATED, REKEYED }

    /**
     * Outcome of fetchIfUnknown. {@code restaurant} for KNOWN / CREATED /
     * REKEYED; {@code previousPlaceId} for REKEYED; {@code rejectReason} for
     * REJECTED. {@code goongCalled} is false only for KNOWN.
     */
    public record FetchResult(FetchStatus status, Restaurant restaurant, String previousPlaceId,
                              String rejectReason, boolean goongCalled) {}

    /**
     * Snapshot-ingestion entry point (never refreshes):
     *   - place_id already stored, whatever its age → KNOWN, no Goong call
     *   - otherwise one Place Detail; no detail → NOT_FOUND; incomplete → INVALID
     *   - {@code rejectReason} non-empty (coordinates / region) → REJECTED, NOT saved
     *   - else saved: CREATED, or REKEYED when the fingerprint matched a stored
     *     row under another place_id (that row takes the new id, as in upsert)
     *
     * Goong failures propagate (budget exhausted, run aborted, transport) —
     * the snapshot runner decides what they mean; nothing is saved then.
     */
    @Transactional
    public FetchResult fetchIfUnknown(String goongPlaceId,
                                      Function<GoongPlaceDTO, Optional<String>> rejectReason) {
        Optional<Restaurant> known = restaurantRepository.findByGoongPlaceId(goongPlaceId);
        if (known.isPresent()) {
            return new FetchResult(FetchStatus.KNOWN, known.get(), null, null, false);
        }
        Optional<GoongPlaceDTO> dto = goongClient.placeDetail(goongPlaceId);
        if (dto.isEmpty()) {
            return new FetchResult(FetchStatus.NOT_FOUND, null, null, null, true);
        }
        if (!isValidPlace(dto.get())) {
            return new FetchResult(FetchStatus.INVALID, null, null, null, true);
        }
        Optional<String> reason = rejectReason.apply(dto.get());
        if (reason.isPresent()) {
            return new FetchResult(FetchStatus.REJECTED, null, null, reason.get(), true);
        }
        GoongPlaceDTO place = dto.get();
        String fingerprint = computeFingerprint(
            place.name(), place.geometry().location().lat(), place.geometry().location().lng());
        Optional<String> previousId = restaurantRepository.findByDedupFingerprint(fingerprint)
            .map(Restaurant::getGoongPlaceId);
        Restaurant saved = upsert(place);
        return previousId.isPresent()
            ? new FetchResult(FetchStatus.REKEYED, saved, previousId.get(), null, true)
            : new FetchResult(FetchStatus.CREATED, saved, null, null, true);
    }

    // ── Internal ──────────────────────────────────────────

    /**
     * Idempotent insert-or-update. Two-stage dedup:
     *   1. Primary:   goong_place_id  (the natural key Goong gives us)
     *   2. Secondary: dedup_fingerprint  (catches reissued IDs, same place
     *      with a slightly different lat/lng down to ~10m)
     *
     * The fingerprint is computed BEFORE the lookup so the secondary probe
     * can fire on every upsert. If the primary lookup misses but the
     * fingerprint matches an existing row, we re-key that row to the new
     * Goong ID rather than creating a duplicate.
     */
    private Restaurant upsert(GoongPlaceDTO dto) {
        String fingerprint = computeFingerprint(
            dto.name(),
            dto.geometry().location().lat(),
            dto.geometry().location().lng()
        );

        Optional<Restaurant> existing = restaurantRepository.findByGoongPlaceId(dto.placeId())
            .or(() -> restaurantRepository.findByDedupFingerprint(fingerprint));

        return existing
            .map(r -> {
                String previousId = r.getGoongPlaceId();
                mapper.mergeInto(r, dto);
                r.setLastSyncedAt(Instant.now());
                r.setDedupFingerprint(fingerprint);
                // If we matched by fingerprint (different place_id), re-key
                // to the latest Goong ID. The UNIQUE constraint will surface
                // any genuine collision as a constraint violation, which is
                // the right failure mode.
                if (!dto.placeId().equals(previousId)) {
                    log.info("Restaurant id={} re-keyed: place_id {} → {} (fingerprint match)",
                        r.getId(), previousId, dto.placeId());
                    r.setGoongPlaceId(dto.placeId());
                }
                log.debug("Refreshed restaurant {} (place_id={})", r.getId(), dto.placeId());
                return r;
            })
            .orElseGet(() -> {
                Restaurant fresh = mapper.toEntity(dto);
                fresh.setLastSyncedAt(Instant.now());
                fresh.setDedupFingerprint(fingerprint);
                Restaurant saved = restaurantRepository.save(fresh);
                log.info("Cached new restaurant {} (place_id={})", saved.getId(), dto.placeId());
                return saved;
            });
    }

    /**
     * sha1(lower(unaccent(name)) || '|' || round(lat,4) || '|' || round(lng,4)).
     * 4-decimal rounding ≈ 11m at HCMC latitude — the right granularity
     * for "same physical restaurant" without conflating neighbours.
     *
     * Using SHA-1 because the column is 64 chars (40 for SHA-1 hex). Not
     * a security-sensitive hash; collision risk at our data size is nil.
     */
    static String computeFingerprint(String name, double lat, double lng) {
        String norm = name == null ? "" : Normalizer.normalize(name, Normalizer.Form.NFD)
            .replaceAll("\\p{InCombiningDiacriticalMarks}+", "")
            .toLowerCase(Locale.ROOT)
            .trim();
        String input = norm + "|"
            + String.format(Locale.ROOT, "%.4f", lat) + "|"
            + String.format(Locale.ROOT, "%.4f", lng);
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            byte[] hash = md.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(hash.length * 2);
            for (byte b : hash) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            // SHA-1 is required by every Java runtime — this is unreachable.
            throw new IllegalStateException("SHA-1 not available", e);
        }
    }

    /**
     * Defensive filter — Goong occasionally returns places without coordinates
     * or place_id. Skipping these prevents NULL violations on required columns.
     */
    private boolean isValidPlace(GoongPlaceDTO dto) {
        return StringUtils.hasText(dto.placeId())
            && StringUtils.hasText(dto.name())
            && dto.geometry() != null
            && dto.geometry().location() != null
            && dto.geometry().location().lat() != null
            && dto.geometry().location().lng() != null;
    }
}
