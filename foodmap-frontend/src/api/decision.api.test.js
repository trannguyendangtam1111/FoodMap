import { beforeEach, describe, expect, it, vi } from 'vitest';
import apiClient from './client';
import { DecisionApiError, decideToday, isAbortError, normalizeDecision } from './decision.api';
import { BANH_MI, userDecision } from '../test/decisionFixtures';

vi.mock('./client', () => ({ default: { get: vi.fn() } }));

const lastCall = () => apiClient.get.mock.lastCall;

function httpError(status, { headers = {}, data = { message: 'server says no' } } = {}) {
  return Object.assign(new Error(`HTTP ${status}`), { response: { status, headers, data } });
}

beforeEach(() => {
  apiClient.get.mockReset();
  apiClient.get.mockResolvedValue({ data: userDecision(BANH_MI) });
});

describe('decideToday — request', () => {
  it('calls GET /decisions/today on the shared API client', async () => {
    await decideToday();
    expect(apiClient.get).toHaveBeenCalledTimes(1);
    expect(lastCall()[0]).toBe('/decisions/today'); // baseURL adds /api/v1
  });

  it('sends lat and lng together', async () => {
    await decideToday({ lat: 10.78, lng: 106.69, radius: 1000 });
    expect(lastCall()[1].params).toEqual({ lat: 10.78, lng: 106.69, radius: 1000 });
  });

  it('omits location when it is unavailable or incomplete', async () => {
    await decideToday({});
    expect(lastCall()[1].params).toEqual({});
    await decideToday({ lat: 10.78 }); // never half a coordinate
    expect(lastCall()[1].params).toEqual({});
    await decideToday({ lat: undefined, lng: undefined });
    expect(lastCall()[1].params).toEqual({});
  });

  it('sends radius=1000 when given', async () => {
    await decideToday({ lat: 1, lng: 2, radius: 1000 });
    expect(lastCall()[1].params.radius).toBe(1000);
  });

  it('sends mood only when requested', async () => {
    await decideToday({});
    expect(lastCall()[1].params).not.toHaveProperty('mood');
    await decideToday({ mood: 'mon-nuoc' });
    expect(lastCall()[1].params.mood).toBe('mon-nuoc');
  });

  it('serialises exclude as a comma-separated list, and omits it when empty', async () => {
    await decideToday({ exclude: [42, 17] });
    expect(lastCall()[1].params.exclude).toBe('42,17');
    await decideToday({ exclude: [] });
    expect(lastCall()[1].params).not.toHaveProperty('exclude');
  });

  it('passes the AbortSignal through and rethrows the cancellation untouched', async () => {
    const controller = new AbortController();
    const cancel = Object.assign(new Error('canceled'), { name: 'CanceledError', code: 'ERR_CANCELED' });
    apiClient.get.mockRejectedValueOnce(cancel);
    const promise = decideToday({ signal: controller.signal });
    expect(lastCall()[1].signal).toBe(controller.signal);
    await expect(promise).rejects.toBe(cancel);
    expect(isAbortError(cancel)).toBe(true);
  });
});

describe('decideToday — response', () => {
  it('returns the backend decision unchanged in meaning', async () => {
    const body = userDecision(BANH_MI, { distanceMeters: 630 });
    apiClient.get.mockResolvedValueOnce({ data: body });
    await expect(decideToday()).resolves.toEqual(body);
  });

  it('normalises missing or malformed fields without inventing values', () => {
    expect(normalizeDecision({ restaurant: null, candidateCount: 0 })).toEqual({
      decisionId: null, restaurant: null, distanceMeters: null, reasons: [], candidateCount: 0,
      fallbackLevel: null, locationSource: null, radiusMeters: null, mood: null, moodMatched: null,
    });
    const n = normalizeDecision({ restaurant: BANH_MI, reasons: [null, { code: 'X', label: '  ' }, { code: 'AREA', label: 'Quanh trung tâm TP.HCM' }], distanceMeters: 'far' });
    expect(n.reasons).toEqual([{ code: 'AREA', label: 'Quanh trung tâm TP.HCM' }]);
    expect(n.distanceMeters).toBeNull();
  });
});

describe('decideToday — errors', () => {
  it('keeps the HTTP status and server message', async () => {
    apiClient.get.mockRejectedValueOnce(httpError(400, { data: { message: 'exclude accepts at most 50 IDs.' } }));
    const error = await decideToday().catch((e) => e);
    expect(error).toBeInstanceOf(DecisionApiError);
    expect(error).toMatchObject({ status: 400, message: 'exclude accepts at most 50 IDs.', retryAfterSeconds: null });
  });

  it('reads Retry-After on 429 when the header is readable', async () => {
    apiClient.get.mockRejectedValueOnce(httpError(429, { headers: { 'retry-after': '7' } }));
    await expect(decideToday()).rejects.toMatchObject({ status: 429, retryAfterSeconds: 7 });
    apiClient.get.mockRejectedValueOnce(httpError(429)); // header hidden by CORS
    await expect(decideToday()).rejects.toMatchObject({ status: 429, retryAfterSeconds: null });
  });

  it('marks network failures with status null', async () => {
    apiClient.get.mockRejectedValueOnce(Object.assign(new Error('Network Error'), { code: 'ERR_NETWORK' }));
    await expect(decideToday()).rejects.toMatchObject({ name: 'DecisionApiError', status: null });
  });

  it('keeps 5xx statuses', async () => {
    apiClient.get.mockRejectedValueOnce(httpError(503));
    await expect(decideToday()).rejects.toMatchObject({ status: 503 });
  });
});

// ── Food page (Part 8) ───────────────────────────────────────────────────────

describe('getFoodRestaurants / decideFoodRestaurant', () => {
  const SCOPE = { locationSource: 'CITY_CENTER', area: null, radiusMeters: 5000, radiusWidened: true, label: 'Quanh trung tâm TP.HCM' };
  const item = (id, evidence, extra = {}) => ({ restaurant: { id, name: `Quán ${id}`, foodKeys: ['pho'], category: 'noodle' }, evidence, ...extra });

  it('asks the food\'s list with the scope params and the page — never anything to classify', async () => {
    const { getFoodRestaurants } = await import('./decision.api');
    apiClient.get.mockResolvedValueOnce({ data: { scope: SCOPE, food: { key: 'bun-bo', label: 'Bún bò' }, totalElements: 0, page: 2, size: 12, items: [] } });
    await getFoodRestaurants({ foodKey: 'bun-bo', params: { area: 'quan-1' }, page: 2 });
    expect(lastCall()).toEqual(['/decisions/foods/bun-bo/restaurants', { params: { area: 'quan-1', page: 2, size: 12 }, signal: undefined }]);
  });

  it('keeps the server\'s items and order; drops items without evidence; distance only for USER', async () => {
    const { normalizeFoodRestaurants } = await import('./decision.api');
    const body = {
      scope: SCOPE, food: { key: 'pho', label: 'Phở' }, totalElements: 4, page: 0, size: 12,
      items: [
        item(3, { source: 'MENU', dishName: 'Phở Bò Tái' }, { distanceMeters: 300 }),
        item(1, { source: 'NAME' }),
        item(2, null),
        { restaurant: null, evidence: { source: 'NAME' } },
      ],
    };
    const n = normalizeFoodRestaurants(body);
    expect(n.items.map((i) => i.restaurant.id)).toEqual([3, 1]);
    expect(n.items[0]).toMatchObject({ distanceMeters: null, evidence: { source: 'MENU', dishName: 'Phở Bò Tái' } });
    expect(n.items[1].evidence).toEqual({ source: 'NAME', dishName: null });
    expect(n.totalElements).toBe(4);
    expect(n.scope).toMatchObject({ radiusMeters: 5000, radiusWidened: true });
    const user = normalizeFoodRestaurants({ ...body, scope: { ...SCOPE, locationSource: 'USER' } });
    expect(user.items[0].distanceMeters).toBe(300);
  });

  it('an unknown food is a 404 DecisionApiError', async () => {
    const { getFoodRestaurants } = await import('./decision.api');
    apiClient.get.mockRejectedValueOnce(httpError(404, { data: { message: "Unknown food 'xyz'." } }));
    const error = await getFoodRestaurants({ foodKey: 'xyz' }).catch((e) => e);
    expect(error).toBeInstanceOf(DecisionApiError);
    expect(error.status).toBe(404);
  });

  it('the suggestion: /decisions/today with food, scope and the restaurants already shown', async () => {
    const { decideFoodRestaurant } = await import('./decision.api');
    apiClient.get.mockResolvedValueOnce({ data: { restaurant: null, emptyReason: 'ALL_SHOWN', food: { key: 'pho', label: 'Phở' }, radiusWidened: false } });
    const d = await decideFoodRestaurant({ foodKey: 'pho', params: { lat: 10.78, lng: 106.69 }, exclude: [1, 9] });
    expect(lastCall()).toEqual(['/decisions/today', { params: { lat: 10.78, lng: 106.69, food: 'pho', exclude: '1,9' }, signal: undefined }]);
    expect(d).toMatchObject({ restaurant: null, emptyReason: 'ALL_SHOWN', food: { key: 'pho', label: 'Phở' } });
  });
});
