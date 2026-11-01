import apiClient from './client';
import { toDecisionApiError } from './decision.api';

/**
 * GET /api/v1/catalog — the server-owned food vocabulary and the discovery
 * areas currently offered. Public fields only: no keywords, no area centres.
 * The frontend keeps no food or area list of its own.
 */
export function normalizeCatalog(data) {
  const d = data ?? {};
  return {
    version: typeof d.version === 'string' ? d.version : null,
    foods: (Array.isArray(d.foods) ? d.foods : [])
      .filter((f) => f && typeof f.key === 'string' && typeof f.label === 'string')
      .map((f) => ({
        key: f.key,
        label: f.label,
        kind: f.kind ?? null,
        parentCategory: typeof f.parentCategory === 'string' ? f.parentCategory : null,
        tone: typeof f.tone === 'string' ? f.tone : null,
        artKey: typeof f.artKey === 'string' ? f.artKey : f.key,
        discoverable: f.discoverable === true,
      })),
    // Server order (display order). Only key, label and order are ever read.
    areas: (Array.isArray(d.areas) ? d.areas : [])
      .filter((a) => a && typeof a.key === 'string' && typeof a.label === 'string')
      .map((a) => ({ key: a.key, label: a.label, order: Number.isFinite(a.order) ? a.order : null })),
  };
}

export async function getCatalog({ signal } = {}) {
  try {
    const { data } = await apiClient.get('/catalog', { signal });
    return normalizeCatalog(data);
  } catch (error) {
    throw toDecisionApiError(error);
  }
}

// ── Search box: which foods a query refers to ────────────────────────────────

/**
 * Keeps the server's answer as sent: suggestions in its order, and `intent`,
 * the one food the WHOLE query names (or null — then the query is not a food,
 * e.g. a restaurant name). Only key and label; the frontend matches nothing.
 */
export function normalizeFoodMatch(data) {
  const d = data ?? {};
  const ref = (f) => (f && typeof f.key === 'string' && typeof f.label === 'string' ? { key: f.key, label: f.label } : null);
  return {
    foods: (Array.isArray(d.foods) ? d.foods : []).map(ref).filter(Boolean),
    intent: ref(d.intent),
  };
}

const MATCH_MEMO_SIZE = 30;
const matchMemo = new Map();   // trimmed query → Promise (fulfilled answers only)

/** Tests only. */
export function resetFoodMatchMemo() {
  matchMemo.clear();
}

/**
 * GET /api/v1/catalog/foods/match?q= — read-only, resolved on the server.
 * The same query asked twice (the suggestions, then Enter) costs one request.
 */
export function matchFoods(query) {
  const q = String(query ?? '').trim();
  if (!q) return Promise.resolve({ foods: [], intent: null });
  if (!matchMemo.has(q)) {
    const promise = apiClient.get('/catalog/foods/match', { params: { q } })
      .then(({ data }) => normalizeFoodMatch(data))
      .catch((error) => {
        matchMemo.delete(q);   // never remember a failure
        throw toDecisionApiError(error);
      });
    matchMemo.set(q, promise);
    if (matchMemo.size > MATCH_MEMO_SIZE) matchMemo.delete(matchMemo.keys().next().value);
  }
  return matchMemo.get(q);
}
