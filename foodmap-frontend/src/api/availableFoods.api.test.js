import { beforeEach, describe, expect, it, vi } from 'vitest';
import apiClient from './client';
import { DecisionApiError, getAvailableFoods, normalizeAvailableFoods } from './decision.api';
import { getCatalog } from './catalog.api';
import { catalogBody, cityFoods, httpError } from '../test/foodFixtures';

vi.mock('./client', () => ({ default: { get: vi.fn() } }));

beforeEach(() => apiClient.get.mockReset());

describe('getAvailableFoods', () => {
  it('asks for every kind, with exactly the scope params it is given', async () => {
    apiClient.get.mockResolvedValueOnce({ data: cityFoods() });
    await getAvailableFoods({ params: { area: 'quan-1' } });
    expect(apiClient.get).toHaveBeenCalledWith('/decisions/foods/available',
      expect.objectContaining({ params: { area: 'quan-1', kinds: 'MEAL,DRINK,DESSERT' } }));
  });

  it('keeps the server order, labels and counts — no sorting, no renaming', () => {
    const r = normalizeAvailableFoods(cityFoods());
    expect(r.foods.map((f) => f.key)).toEqual(['ca-phe', 'tra-sua', 'bun', 'pho', 'pizza', 'sushi']);
    expect(r.foods[1]).toMatchObject({ label: 'Trà sữa', count: 2, radiusMeters: 5000, radiusWidened: true, kind: 'DRINK' });
    expect(r.scope.label).toBe('Quanh trung tâm TP.HCM');
    expect(r.unavailableCount).toBe(4);
  });

  it('drops only what cannot be shown honestly: no positive count, no key or label', () => {
    const body = cityFoods();
    body.foods.push({ key: 'kem', label: 'Kem', count: 0 }, { label: 'Không khóa', count: 2 }, { key: 'x', count: 3 }, null);
    expect(normalizeAvailableFoods(body).foods).toHaveLength(6);
  });

  it('429 carries Retry-After; other failures carry their status (network: null)', async () => {
    apiClient.get.mockRejectedValueOnce(httpError(429, { headers: { 'retry-after': '3' } }));
    await expect(getAvailableFoods()).rejects.toMatchObject({ status: 429, retryAfterSeconds: 3 });
    apiClient.get.mockRejectedValueOnce(new Error('Network Error'));
    const error = await getAvailableFoods().catch((e) => e);
    expect(error).toBeInstanceOf(DecisionApiError);
    expect(error.status).toBeNull();
  });
});

describe('getCatalog', () => {
  it('reads only key, label and order of areas, in server order', async () => {
    const body = catalogBody();
    body.areas[0] = { ...body.areas[0], center: { lat: 1, lng: 2 }, radiusMeters: 2000 };   // must be ignored
    apiClient.get.mockResolvedValueOnce({ data: body });
    const catalog = await getCatalog();
    expect(catalog.areas.map((a) => a.key)).toEqual(['quan-1', 'quan-3', 'binh-thanh', 'thu-duc']);
    expect(Object.keys(catalog.areas[0])).toEqual(['key', 'label', 'order']);
  });
});
