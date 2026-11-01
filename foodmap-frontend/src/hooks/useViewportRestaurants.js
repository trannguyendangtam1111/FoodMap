import { useCallback, useEffect, useMemo, useState } from 'react';
import { restaurantApi } from '../api/restaurant.api';
import { foodApi } from '../api/food.api';
import { toDecisionApiError } from '../api/decision.api';
import { useDebounce } from './useDebounce';

/**
 * Fetches restaurants for display on the map. Operates in three modes:
 *
 * VIEWPORT MODE (default)
 *   Triggered whenever setViewport() is called (after map pan/zoom).
 *   Debounced 400ms — one request per completed gesture.
 *   Cancels in-flight requests when a newer viewport arrives.
 *
 * DISH-FILTER MODE
 *   Triggered when setActiveFoodId(foodId) is called (user picks a dish).
 *   Calls /foods/{id}/restaurants instead of /restaurants/viewport.
 *   Viewport changes are ignored while a food filter is active.
 *   Call clearFoodFilter() to return to viewport mode.
 *
 * SEARCH MODE
 *   Triggered when setSearchQuery(query) is called (user submits text search).
 *   Calls /restaurants/search?query=… — returns best-match restaurants globally.
 *   Both viewport and dish-filter are ignored while a search query is active.
 *   Call clearSearch() to return to viewport mode.
 *
 * error  null, or a DecisionApiError { status, retryAfterSeconds, timeout } —
 *        never the server's text; the previous restaurants stay in place.
 * retry  asks the current mode again (the same request as before).
 *
 * restore  { restaurants, center } from the map's in-memory memory (Back to
 *          /map): shown until the first answer replaces them, so the list
 *          and pins don't flash empty. Nothing is fetched because of it.
 */
export function useViewportRestaurants({ restore = null } = {}) {
  const [viewport, setViewport]           = useState(null);
  const [activeFoodId, setActiveFoodId]   = useState(null);
  const [searchQuery, setSearchQuery]     = useState(null);
  const [restaurants, setRestaurants]     = useState(() => restore?.restaurants ?? []);
  const [isLoading, setIsLoading]         = useState(false);
  const [error, setError]                 = useState(null);
  const [indexingCells, setIndexingCells] = useState(0);
  const [attempt, setAttempt]             = useState(0);

  // 600ms debounce on top of the map's moveend event. moveend already
  // suppresses requests during continuous drag; the extra delay collapses
  // repeated pan/zoom gestures (zoom-in, immediately pan, settle) into one
  // backend hit — protecting the Goong RPS budget on the way through.
  const debouncedViewport = useDebounce(viewport, 600);

  // ── SEARCH MODE ───────────────────────────────────────────────────────────
  useEffect(() => {
    if (!searchQuery) return;

    const controller = new AbortController();
    setIsLoading(true);
    setError(null);

    restaurantApi
      .search({ query: searchQuery, size: 50 }, { signal: controller.signal })
      .then(({ data }) => {
        // Guard: if the browser received the response just before abort() fired,
        // Axios may still resolve the promise. Discard stale results here.
        if (controller.signal.aborted) return;
        setRestaurants(Array.isArray(data) ? data : (data?.content ?? []));
      })
      .catch((err) => {
        if (err.code !== 'ERR_CANCELED') {
          setError(toDecisionApiError(err));
        }
      })
      .finally(() => {
        if (!controller.signal.aborted) setIsLoading(false);
      });

    return () => controller.abort();
  }, [searchQuery, attempt]);

  // ── DISH-FILTER MODE ──────────────────────────────────────────────────────
  useEffect(() => {
    if (!activeFoodId) return;

    const controller = new AbortController();
    setIsLoading(true);
    setError(null);

    foodApi
      .getRestaurants(activeFoodId, { size: 50 }, { signal: controller.signal })
      .then(({ data }) => {
        if (controller.signal.aborted) return;
        setRestaurants(Array.isArray(data) ? data : (data?.content ?? []));
      })
      .catch((err) => {
        if (err.code !== 'ERR_CANCELED') {
          setError(toDecisionApiError(err));
        }
      })
      .finally(() => {
        if (!controller.signal.aborted) setIsLoading(false);
      });

    return () => controller.abort();
  }, [activeFoodId, attempt]);

  // ── VIEWPORT MODE ─────────────────────────────────────────────────────────
  // Skipped while a food filter or text search is active.
  useEffect(() => {
    if (!debouncedViewport || activeFoodId || searchQuery) return;

    const controller = new AbortController();
    setIsLoading(true);
    setError(null);

    restaurantApi
      .getViewport(debouncedViewport, { signal: controller.signal })
      .then(({ data, headers }) => {
        // Guard against stale responses: if abort() was called after the browser
        // already received the response, Axios may still resolve — discard here
        // so an old (possibly empty) payload never clears valid current results.
        if (controller.signal.aborted) {
          console.debug('[viewport] discarding stale response for aborted request');
          return;
        }
        const restaurants = Array.isArray(data) ? data : (data?.content ?? []);
        console.debug('[viewport] applying response, count:', restaurants.length,
          '| sw:', debouncedViewport.swLat, debouncedViewport.swLng,
          '| ne:', debouncedViewport.neLat, debouncedViewport.neLng);
        setRestaurants(restaurants);
        const enqueued = Number(headers?.['x-indexing-cells'] ?? 0);
        setIndexingCells(Number.isFinite(enqueued) ? enqueued : 0);
      })
      .catch((err) => {
        if (err.code !== 'ERR_CANCELED') {
          setError(toDecisionApiError(err));
        }
      })
      .finally(() => {
        if (!controller.signal.aborted) setIsLoading(false);
      });

    return () => controller.abort();
  }, [debouncedViewport, activeFoodId, searchQuery, attempt]);

  // Geographic centre of the current viewport — used by the sidebar to rank
  // restaurants by proximity to "where the user is looking". Memoised so that
  // the reference is stable across renders when the viewport hasn't changed.
  const restoredCenter = restore?.center ?? null;
  const mapCenter = useMemo(() => {
    if (!debouncedViewport) return restoredCenter;
    return {
      lat: (debouncedViewport.swLat + debouncedViewport.neLat) / 2,
      lng: (debouncedViewport.swLng + debouncedViewport.neLng) / 2,
    };
  }, [debouncedViewport, restoredCenter]);

  // ── Helpers ───────────────────────────────────────────────────────────────

  const clearFoodFilter = () => {
    setActiveFoodId(null);
    // Keep stale restaurants visible until the next viewport fetch replaces them.
  };

  const clearSearch = () => {
    setSearchQuery(null);
    // Keep stale restaurants visible until the next viewport fetch replaces them.
  };

  const retry = useCallback(() => setAttempt((n) => n + 1), []);

  return {
    restaurants,
    isLoading,
    error,
    indexingCells,
    activeFoodId,
    searchQuery,
    mapCenter,
    setViewport,
    setActiveFoodId,
    setSearchQuery,
    clearFoodFilter,
    clearSearch,
    retry,
  };
}
