import apiClient from './client';

/**
 * GET /api/v1/decisions/today — "Ăn gì hôm nay?" (anonymous, read-only,
 * rate-limited per IP). The server picks the restaurant; this module only
 * builds the request and normalises the response and errors.
 *
 * Contract (Part 5):
 *   lat, lng   sent together or not at all → without them the server uses
 *              its city-centre pool (locationSource CITY_CENTER, level 3)
 *   radius     metres, 1..5000 (server default 1000)
 *   mood       optional; supported: "mon-nuoc"
 *   exclude    comma-separated restaurant IDs, at most 50
 * 200 with `restaurant: null` means "no candidate" — a normal outcome.
 */

/** Thrown for HTTP failures and network errors; cancellations are rethrown as-is. */
export class DecisionApiError extends Error {
  constructor({ status = null, retryAfterSeconds = null, serverMessage = null, cause } = {}) {
    super(serverMessage || (status ? `Decision request failed with HTTP ${status}` : 'Decision request failed (network)'));
    this.name = 'DecisionApiError';
    this.status = status;                       // null → no response (network/CORS)
    this.retryAfterSeconds = retryAfterSeconds; // 429 only, when the header is readable
    this.cause = cause;
  }
}

export const isAbortError = (error) =>
  error?.name === 'AbortError' || error?.name === 'CanceledError' || error?.code === 'ERR_CANCELED';

function parseRetryAfter(value) {
  if (value == null || value === '') return null;
  const seconds = Number(value);
  if (Number.isFinite(seconds)) return Math.max(0, Math.ceil(seconds));
  const at = Date.parse(value); // HTTP-date form
  return Number.isFinite(at) ? Math.max(0, Math.ceil((at - Date.now()) / 1000)) : null;
}

/** Keeps the backend's shape; only guards types so the UI never renders junk. */
export function normalizeDecision(data) {
  const d = data ?? {};
  return {
    decisionId: d.decisionId ?? null,
    restaurant: d.restaurant && d.restaurant.id != null ? d.restaurant : null,
    distanceMeters: Number.isFinite(d.distanceMeters) ? d.distanceMeters : null,
    reasons: Array.isArray(d.reasons)
      ? d.reasons.filter((r) => r && typeof r.label === 'string' && r.label.trim() !== '')
      : [],
    candidateCount: Number.isFinite(d.candidateCount) ? d.candidateCount : 0,
    fallbackLevel: Number.isInteger(d.fallbackLevel) ? d.fallbackLevel : null,
    locationSource: d.locationSource ?? null,
    radiusMeters: Number.isFinite(d.radiusMeters) ? d.radiusMeters : null,
    mood: d.mood ?? null,
    moodMatched: typeof d.moodMatched === 'boolean' ? d.moodMatched : null,
  };
}

/**
 * @param {{ lat?: number, lng?: number, radius?: number, mood?: string,
 *           exclude?: number[], signal?: AbortSignal }} options
 * @returns {Promise<ReturnType<typeof normalizeDecision>>}
 */
export async function decideToday({ lat, lng, radius, mood, exclude, signal } = {}) {
  const params = {};
  if (Number.isFinite(lat) && Number.isFinite(lng)) {
    params.lat = lat;
    params.lng = lng;
  }
  if (radius != null) params.radius = radius;
  if (mood) params.mood = mood;
  if (exclude?.length) params.exclude = exclude.join(',');

  try {
    const { data } = await apiClient.get('/decisions/today', { params, signal });
    return normalizeDecision(data);
  } catch (error) {
    throw toDecisionApiError(error);
  }
}

/** Cancellations pass through untouched; everything else becomes a DecisionApiError. */
export function toDecisionApiError(error) {
  if (isAbortError(error)) return error;
  const response = error?.response;
  return new DecisionApiError({
    status: response?.status ?? null,
    // Readable same-origin, or cross-origin because the server exposes it.
    retryAfterSeconds: response?.status === 429 ? parseRetryAfter(response.headers?.['retry-after']) : null,
    serverMessage: typeof response?.data?.message === 'string' ? response.data.message : null,
    cause: error,
  });
}

// ── "Thèm gì?" ───────────────────────────────────────────────────────────────

/** Every kind, explicitly (Master Specification §9). */
export const ALL_KINDS = 'MEAL,DRINK,DESSERT';

const toInt = (v) => (Number.isFinite(v) ? Math.trunc(v) : null);

/**
 * Keeps the server's foods exactly as sent — same order, labels and counts —
 * and drops only entries the UI could not render honestly (no key, no label,
 * no positive count). Nothing is sorted, merged or renamed here.
 */
export function normalizeAvailableFoods(data) {
  const d = data ?? {};
  const s = d.scope ?? {};
  return {
    scope: normalizeScope(s),
    foods: (Array.isArray(d.foods) ? d.foods : [])
      .filter((f) => f && typeof f.key === 'string' && typeof f.label === 'string' && toInt(f.count) > 0)
      .map((f) => ({
        key: f.key,
        label: f.label,
        kind: typeof f.kind === 'string' ? f.kind : null,
        parentCategory: typeof f.parentCategory === 'string' ? f.parentCategory : null,
        tone: typeof f.tone === 'string' ? f.tone : null,
        count: toInt(f.count),
        // the server's own word: true = the count is a lower bound ("at least")
        countCapped: f.countCapped === true,
        radiusMeters: toInt(f.radiusMeters),
        radiusWidened: f.radiusWidened === true,
      })),
    unavailableCount: Math.max(0, toInt(d.unavailableCount) ?? 0),
  };
}

/**
 * GET /api/v1/decisions/foods/available — foods with at least one restaurant
 * in the scope, with real counts, in catalog order. `params` comes from
 * toScopeParams (lat+lng, area, or nothing).
 */
export async function getAvailableFoods({ params = {}, kinds = ALL_KINDS, signal } = {}) {
  try {
    const { data } = await apiClient.get('/decisions/foods/available', { params: { ...params, kinds }, signal });
    return normalizeAvailableFoods(data);
  } catch (error) {
    throw toDecisionApiError(error);
  }
}

/** The server's ScopeDTO: source, area key/label, base radius, phrase — never coordinates. */
function normalizeScope(s = {}) {
  return {
    locationSource: typeof s.locationSource === 'string' ? s.locationSource : null,
    area: s.area && typeof s.area.key === 'string' ? { key: s.area.key, label: s.area.label ?? s.area.key } : null,
    radiusMeters: toInt(s.radiusMeters),
    radiusWidened: s.radiusWidened === true,
    label: typeof s.label === 'string' ? s.label : null,
  };
}

// ── Cà mèn: the food decision ────────────────────────────────────────────────

/**
 * Keeps the server's choice exactly as sent. `food: null` (with an
 * emptyReason: NO_FOOD_IN_SCOPE or ALL_SHOWN) is a normal answer, not an error.
 */
export function normalizeFoodDecision(data) {
  const d = data ?? {};
  const f = d.food;
  const food = f && typeof f.key === 'string' && typeof f.label === 'string'
    ? {
      key: f.key,
      label: f.label,
      kind: typeof f.kind === 'string' ? f.kind : null,
      parentCategory: typeof f.parentCategory === 'string' ? f.parentCategory : null,
      tone: typeof f.tone === 'string' ? f.tone : null,
      artKey: typeof f.artKey === 'string' ? f.artKey : f.key,
    }
    : null;
  return {
    decisionId: d.decisionId ?? null,
    food,
    restaurantCount: food ? Math.max(0, toInt(d.restaurantCount) ?? 0) : 0,
    restaurantCountCapped: food ? d.restaurantCountCapped === true : false,
    radiusMeters: toInt(d.radiusMeters),
    radiusWidened: d.radiusWidened === true,
    scope: normalizeScope(d.scope),
    candidateFoodCount: toInt(d.candidateFoodCount) ?? 0,
    emptyReason: food ? null : (typeof d.emptyReason === 'string' ? d.emptyReason : 'NO_FOOD_IN_SCOPE'),
  };
}

/**
 * GET /api/v1/decisions/food — the server draws ONE food among those
 * available in the scope (uniformly; default kind MEAL), never one of the
 * `exclude` keys (already shown). `params` comes from toScopeParams.
 */
export async function decideFood({ params = {}, exclude, signal } = {}) {
  const query = { ...params };
  if (exclude?.length) query.exclude = exclude.join(',');
  try {
    const { data } = await apiClient.get('/decisions/food', { params: query, signal });
    return normalizeFoodDecision(data);
  } catch (error) {
    throw toDecisionApiError(error);
  }
}

// ── Food page: restaurants for one food ──────────────────────────────────────

/**
 * A restaurant decision for a food (GET /decisions/today?food=): the V1
 * fields plus the food asked for, the area, whether the radius widened and,
 * when restaurant is null, why (NO_FOOD_IN_SCOPE or ALL_SHOWN). The food is
 * never a substitute: the server answers for the requested food or not at all.
 */
export function normalizeFoodRestaurantDecision(data) {
  const d = data ?? {};
  const decision = normalizeDecision(d);
  return {
    ...decision,
    food: d.food && typeof d.food.key === 'string' ? { key: d.food.key, label: d.food.label ?? null } : null,
    area: d.area && typeof d.area.key === 'string' ? { key: d.area.key, label: d.area.label ?? d.area.key } : null,
    radiusWidened: d.radiusWidened === true,
    emptyReason: decision.restaurant ? null : (typeof d.emptyReason === 'string' ? d.emptyReason : 'NO_FOOD_IN_SCOPE'),
  };
}

/**
 * GET /api/v1/decisions/today?food={key} — the server's suggestion of ONE
 * restaurant with that food in the scope (its weighted top-10 draw), never
 * one of `exclude` (restaurant IDs already shown). `params` comes from
 * toScopeParams. Same decision rate limit as the cà mèn.
 */
export async function decideFoodRestaurant({ foodKey, params = {}, exclude, signal } = {}) {
  const query = { ...params, food: foodKey };
  if (exclude?.length) query.exclude = exclude.join(',');
  try {
    const { data } = await apiClient.get('/decisions/today', { params: query, signal });
    return normalizeFoodRestaurantDecision(data);
  } catch (error) {
    throw toDecisionApiError(error);
  }
}

/** Page size of the food page list (the server accepts 1–50). */
export const FOOD_RESTAURANTS_PAGE_SIZE = 12;

/**
 * Keeps the server's list exactly as sent — same order, same items — and
 * drops only items the UI could not render honestly (no restaurant id, no
 * evidence). A distance is kept only for the user's own location.
 */
export function normalizeFoodRestaurants(data) {
  const d = data ?? {};
  const scope = normalizeScope(d.scope);
  const user = scope.locationSource === 'USER';
  const f = d.food;
  return {
    scope,
    food: f && typeof f.key === 'string' && typeof f.label === 'string' ? { key: f.key, label: f.label } : null,
    totalElements: Math.max(0, toInt(d.totalElements) ?? 0),
    // true = totalElements is a lower bound (the server's rows hit its safety cap)
    totalCapped: d.totalCapped === true,
    page: Math.max(0, toInt(d.page) ?? 0),
    size: Math.max(1, toInt(d.size) ?? FOOD_RESTAURANTS_PAGE_SIZE),
    items: (Array.isArray(d.items) ? d.items : [])
      .filter((it) => it && it.restaurant && it.restaurant.id != null
        && (it.evidence?.source === 'NAME' || it.evidence?.source === 'MENU'))
      .map((it) => ({
        restaurant: it.restaurant,
        distanceMeters: user && Number.isFinite(it.distanceMeters) ? it.distanceMeters : null,
        evidence: {
          source: it.evidence.source,
          dishName: it.evidence.source === 'MENU' && typeof it.evidence.dishName === 'string' ? it.evidence.dishName : null,
        },
      })),
  };
}

/**
 * GET /api/v1/decisions/foods/{key}/restaurants — every restaurant of the
 * scope FoodMap knows to have the food, in the server's ranking order
 * (deterministic), one page. totalElements is the whole set: the same number
 * "Thèm gì?" shows for the food in the same scope. Unknown food → 404.
 */
export async function getFoodRestaurants({ foodKey, params = {}, page = 0, size = FOOD_RESTAURANTS_PAGE_SIZE, signal } = {}) {
  try {
    const { data } = await apiClient.get(`/decisions/foods/${encodeURIComponent(foodKey)}/restaurants`, {
      params: { ...params, page, size },
      signal,
    });
    return normalizeFoodRestaurants(data);
  } catch (error) {
    throw toDecisionApiError(error);
  }
}

export const decisionApi = {
  today: decideToday,
  availableFoods: getAvailableFoods,
  food: decideFood,
  foodRestaurant: decideFoodRestaurant,
  foodRestaurants: getFoodRestaurants,
};
