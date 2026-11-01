import { useCallback, useEffect, useState } from 'react';
import { getCatalog } from '../api/catalog.api';

/**
 * useCatalog({ enabled }) — GET /api/v1/catalog at most once per tab.
 *
 * Fetched only when a caller enables it (the area picker opens, a food page
 * needs a label) — never just because the homepage loaded. The answer is kept
 * in module memory; a request in flight is shared (StrictMode-safe); errors
 * are not cached and `retry` asks again.
 *
 * Returns { status: 'idle' | 'loading' | 'ready' | 'error', catalog, error, retry }.
 */
let cached = null;
let inFlight = null;

function load(request) {
  if (cached) return Promise.resolve(cached);
  if (!inFlight) {
    inFlight = request()
      .then((catalog) => {
        cached = catalog;
        return catalog;
      })
      .finally(() => { inFlight = null; });
  }
  return inFlight;
}

/** Tests only. */
export function resetCatalogCache() {
  cached = null;
  inFlight = null;
}

export default function useCatalog({ enabled = true, request = getCatalog } = {}) {
  const [attempt, setAttempt] = useState(0);
  const [result, setResult] = useState(() => (cached ? { status: 'ready', catalog: cached } : { status: 'idle' }));

  useEffect(() => {
    if (!enabled) return undefined;
    if (cached) {
      setResult({ status: 'ready', catalog: cached });
      return undefined;
    }
    let active = true;
    setResult({ status: 'loading' });
    load(request).then(
      (catalog) => active && setResult({ status: 'ready', catalog }),
      (error) => active && setResult({ status: 'error', error }),
    );
    return () => { active = false; };
  }, [enabled, attempt, request]);

  const retry = useCallback(() => setAttempt((n) => n + 1), []);
  return { status: result.status, catalog: result.catalog ?? null, error: result.error ?? null, retry };
}
