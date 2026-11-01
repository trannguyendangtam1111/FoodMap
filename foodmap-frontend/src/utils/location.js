/**
 * requestUserLocation — one-shot browser location for an explicit user
 * action (never call it on mount). Resolves { lat, lng } or null; it never
 * rejects, so a denied, slow or missing location can't block the caller.
 *
 * Budgets:
 *   promptTimeoutMs    how long we wait while the permission prompt is open
 *                      (the user may be reading it — but not forever)
 *   positionTimeoutMs  how long the browser may take for a fix once allowed
 * When the Permissions API reports "denied" we don't call geolocation at all.
 */
export async function requestUserLocation({
  signal,
  promptTimeoutMs = 15_000,
  positionTimeoutMs = 4_000,
  maximumAge = 5 * 60_000,
} = {}) {
  const geo = typeof navigator !== 'undefined' ? navigator.geolocation : undefined;
  if (!geo || signal?.aborted) return null;

  let state = 'prompt';
  try {
    state = (await navigator.permissions?.query({ name: 'geolocation' }))?.state ?? 'prompt';
  } catch {
    // Permissions API missing or not supporting "geolocation" — just ask.
  }
  if (state === 'denied' || signal?.aborted) return null;

  return new Promise((resolve) => {
    let settled = false;
    const finish = (value) => {
      if (settled) return;
      settled = true;
      clearTimeout(timer);
      signal?.removeEventListener('abort', onAbort);
      resolve(value);
    };
    const onAbort = () => finish(null);
    const timer = setTimeout(() => finish(null), state === 'granted' ? positionTimeoutMs : promptTimeoutMs);
    signal?.addEventListener('abort', onAbort, { once: true });

    geo.getCurrentPosition(
      ({ coords }) => {
        const { latitude: lat, longitude: lng } = coords;
        const valid = Number.isFinite(lat) && Number.isFinite(lng) && Math.abs(lat) <= 90 && Math.abs(lng) <= 180;
        finish(valid ? { lat, lng } : null);
      },
      () => finish(null),
      { enableHighAccuracy: false, timeout: positionTimeoutMs, maximumAge },
    );
  });
}
