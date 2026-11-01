import { useCallback, useEffect, useState, useSyncExternalStore } from 'react';
import {
  FOOD_RESTAURANTS_PAGE_SIZE, decideFoodRestaurant, getFoodRestaurants, isAbortError,
} from '../api/decision.api';
import { scopeKey, toScopeParams } from '../utils/scope';

/**
 * useFoodRestaurants(foodKey, scope) — the food page's restaurants for one
 * (food, scope), all chosen and ordered by the server:
 *
 *   suggestion  GET /decisions/today?food=  one restaurant (the server's
 *               weighted draw); "Quán khác" asks again excluding every
 *               restaurant already suggested; ALL_SHOWN when none is left
 *   list        GET /decisions/foods/{key}/restaurants  the ranked list,
 *               page by page ("Xem thêm quán" appends the next page)
 *
 * Module memory, one (food, scope) at a time: it survives in-app navigation
 * (back from a restaurant shows the same suggestion and list, no request),
 * not a reload. Shown restaurant IDs reset when the food or scope changes or
 * on "Xem lại từ đầu". Nothing is ranked, filtered or drawn here.
 *
 * Requests are shared by every mount of the same (food, scope), so React
 * StrictMode's double effect costs no second request. Leaving the page
 * aborts what is still in flight; coming back asks again.
 */
export const SUGGESTION_TIMEOUT_MS = 5000;
export const MAX_EXCLUDED_RESTAURANTS = 50;   // the server's limit for exclude

const IDLE = 'idle';
const LOADING = 'loading';

let entry = null;        // { key, foodKey, scope, seen, suggestion, list }
let controllers = {};    // suggestion | list | more → AbortController
let releaseTimer = null;
let seq = 0;             // increases with every settled suggestion
const listeners = new Set();

const keyOf = (foodKey, scope) => `${foodKey}|${scopeKey(scope)}`;

function set(patch) {
  entry = { ...entry, ...patch };
  listeners.forEach((listener) => listener());
}

function abort(name) {
  controllers[name]?.abort();
  controllers[name] = null;
}

function abortAll() {
  Object.keys(controllers).forEach(abort);
}

function fetchSuggestion() {
  const { key, foodKey, scope, seen } = entry;
  abort('suggestion');
  const controller = new AbortController();
  controllers.suggestion = controller;
  // Too slow: stop waiting and say so (whether or not the aborted request ever settles).
  const timer = setTimeout(() => {
    controller.abort();
    if (entry?.key !== key || controllers.suggestion !== controller) return;
    controllers.suggestion = null;
    set({ suggestion: { ...entry.suggestion, status: 'error', error: { timeout: true }, seq: ++seq } });
  }, SUGGESTION_TIMEOUT_MS);
  set({ suggestion: { ...entry.suggestion, status: LOADING, error: null } });

  decideFoodRestaurant({ foodKey, params: toScopeParams(scope), exclude: seen, signal: controller.signal })
    .then((decision) => {
      if (entry?.key !== key || controller.signal.aborted) return;
      if (decision.restaurant) {
        const id = decision.restaurant.id;
        set({
          seen: [...entry.seen.filter((x) => x !== id), id].slice(-MAX_EXCLUDED_RESTAURANTS),
          suggestion: { status: 'ready', decision, error: null, seq: ++seq },
        });
      } else {
        set({
          suggestion: {
            status: decision.emptyReason === 'ALL_SHOWN' ? 'allShown' : 'empty',
            decision, error: null, seq: ++seq,
          },
        });
      }
    }, (error) => {
      // Aborted: the page went away, a newer request replaced it, or it timed out (handled above).
      if (entry?.key !== key || controller.signal.aborted || isAbortError(error)) return;
      set({ suggestion: { ...entry.suggestion, status: 'error', error, seq: ++seq } });
    })
    .finally(() => {
      clearTimeout(timer);
      if (controllers.suggestion === controller) controllers.suggestion = null;
    });
}

function fetchList(page) {
  const { key, foodKey, scope } = entry;
  const first = page === 0;
  const name = first ? 'list' : 'more';
  abort(name);
  const controller = new AbortController();
  controllers[name] = controller;
  set({ list: first ? { status: LOADING } : { ...entry.list, loadingMore: true, moreError: null } });

  getFoodRestaurants({ foodKey, params: toScopeParams(scope), page, size: FOOD_RESTAURANTS_PAGE_SIZE, signal: controller.signal })
    .then((data) => {
      if (entry?.key !== key || controller.signal.aborted) return;
      if (first) {
        set({ list: { status: 'ready', data, nextPage: 1 } });
        return;
      }
      const known = new Set(entry.list.data.items.map((it) => it.restaurant.id));
      set({
        list: {
          ...entry.list,
          data: { ...data, items: [...entry.list.data.items, ...data.items.filter((it) => !known.has(it.restaurant.id))] },
          nextPage: page + 1,
          loadingMore: false,
          exhausted: data.items.length === 0,
        },
      });
    }, (error) => {
      if (entry?.key !== key || isAbortError(error)) return;
      set({ list: first ? { status: 'error', error } : { ...entry.list, loadingMore: false, moreError: error } });
    })
    .finally(() => {
      if (controllers[name] === controller) controllers[name] = null;
    });
}

/** Start (or keep) the given (food, scope); fetch whatever it does not have yet. */
function ensure(foodKey, scope) {
  clearTimeout(releaseTimer);
  releaseTimer = null;
  const key = keyOf(foodKey, scope);
  if (entry?.key !== key) {
    abortAll();
    entry = { key, foodKey, scope, seen: [], suggestion: { status: IDLE, seq: 0 }, list: { status: IDLE } };
  }
  if (entry.suggestion.status === IDLE) fetchSuggestion();
  if (entry.list.status === IDLE) fetchList(0);
}

/**
 * The page went away. Deferred one tick, so StrictMode's unmount/remount
 * (same key) keeps its requests; otherwise abort what is in flight and mark
 * it to be fetched again on return. Settled answers stay for "Back".
 */
function release() {
  clearTimeout(releaseTimer);
  releaseTimer = setTimeout(() => {
    releaseTimer = null;
    if (!entry) return;
    abortAll();
    const { suggestion, list } = entry;
    set({
      suggestion: suggestion.status === LOADING
        ? (suggestion.decision?.restaurant ? { ...suggestion, status: 'ready' } : { status: IDLE, seq: suggestion.seq ?? 0 })
        : suggestion,
      list: list.status === LOADING ? { status: IDLE } : { ...list, loadingMore: false },
    });
  }, 0);
}

const subscribe = (listener) => {
  listeners.add(listener);
  return () => listeners.delete(listener);
};
const getSnapshot = () => entry;

/** Tests only. */
export function resetFoodRestaurantsMemory() {
  clearTimeout(releaseTimer);
  releaseTimer = null;
  abortAll();
  entry = null;
  seq = 0;
}

export default function useFoodRestaurants(foodKey, scope, { enabled = true } = {}) {
  const key = enabled && scope ? keyOf(foodKey, scope) : null;
  const snapshot = useSyncExternalStore(subscribe, getSnapshot, getSnapshot);
  // Suggestions settled before this mount are restored, not new: no reveal, no announcement.
  const [mountSeq] = useState(() => (entry ? seq : -1));

  useEffect(() => {
    if (!key) return undefined;
    ensure(foodKey, scope);
    return release;
    // `key` identifies (foodKey, scope)
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [key]);

  const current = snapshot?.key === key ? snapshot : null;
  const suggestion = current?.suggestion ?? { status: key ? LOADING : IDLE };
  const list = current?.list ?? { status: key ? LOADING : IDLE };
  const listStatus = list.status === IDLE && key ? LOADING : list.status;
  const items = list.data?.items ?? [];

  return {
    suggestion: {
      ...suggestion,
      status: suggestion.status === IDLE && key ? LOADING : suggestion.status,
      fresh: (suggestion.seq ?? 0) > mountSeq,
    },
    shownCount: current?.seen.length ?? 0,
    list: {
      ...list,
      status: listStatus,
      hasMore: listStatus === 'ready' && !list.exhausted && items.length < (list.data?.totalElements ?? 0),
    },
    more: useCallback(() => { if (entry) fetchSuggestion(); }, []),
    restart: useCallback(() => {
      if (!entry) return;
      set({ seen: [] });
      fetchSuggestion();
    }, []),
    retrySuggestion: useCallback(() => { if (entry) fetchSuggestion(); }, []),
    retryList: useCallback(() => { if (entry) fetchList(0); }, []),
    loadMore: useCallback(() => { if (entry?.list.status === 'ready') fetchList(entry.list.nextPage ?? 1); }, []),
  };
}
