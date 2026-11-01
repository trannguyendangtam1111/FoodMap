import { useCallback, useEffect, useRef, useState } from 'react';
import { getAvailableFoods } from '../api/decision.api';
import { scopeKey, toScopeParams } from '../utils/scope';

/**
 * useAvailableFoods(scope) — GET /decisions/foods/available for the scope.
 *
 * One request per scope: answers are kept in memory for 60 s per scope key
 * (USER keys use 3-decimal coordinates and never leave the tab), and a
 * request already in flight is shared — so React StrictMode's second effect
 * run, or two sections asking at once, cause one network call, not two.
 * Errors are never cached; `retry` asks again. A different scope never shows
 * the previous scope's counts: until its own answer arrives it is 'loading'.
 *
 * Returns { status: 'loading' | 'ready' | 'error', data, error, retry }.
 */
export const AVAILABLE_FOODS_TTL_MS = 60_000;

const cache = new Map();     // key → { data, at }
const inFlight = new Map();  // key → Promise

function fresh(key) {
  const hit = cache.get(key);
  return hit && Date.now() - hit.at < AVAILABLE_FOODS_TTL_MS ? hit.data : null;
}

function load(key, params, request) {
  const hit = fresh(key);
  if (hit) return Promise.resolve(hit);
  if (!inFlight.has(key)) {
    const promise = request({ params })
      .then((data) => {
        cache.set(key, { data, at: Date.now() });
        return data;
      })
      .finally(() => inFlight.delete(key));
    inFlight.set(key, promise);
  }
  return inFlight.get(key);
}

/** Tests only. */
export function resetAvailableFoodsCache() {
  cache.clear();
  inFlight.clear();
}

export default function useAvailableFoods(scope, { request = getAvailableFoods } = {}) {
  const key = scopeKey(scope);
  const scopeRef = useRef(scope);
  scopeRef.current = scope;
  const [attempt, setAttempt] = useState(0);
  const [result, setResult] = useState(() => {
    const hit = fresh(key);
    return hit ? { key, status: 'ready', data: hit } : { key, status: 'loading' };
  });

  useEffect(() => {
    let active = true;
    const hit = fresh(key);
    if (hit) {
      setResult({ key, status: 'ready', data: hit });
      return undefined;
    }
    setResult({ key, status: 'loading' });
    load(key, toScopeParams(scopeRef.current), request).then(
      (data) => active && setResult({ key, status: 'ready', data }),
      (error) => active && setResult({ key, status: 'error', error }),
    );
    return () => { active = false; };
  }, [key, attempt, request]);

  const retry = useCallback(() => setAttempt((n) => n + 1), []);

  // Never show another scope's answer while this one loads.
  const current = result.key === key ? result : { key, status: 'loading' };
  return { status: current.status, data: current.data ?? null, error: current.error ?? null, retry };
}
