import { beforeEach, describe, expect, it, vi } from 'vitest';
import apiClient from './client';
import { matchFoods, normalizeFoodMatch, resetFoodMatchMemo } from './catalog.api';

vi.mock('./client', () => ({ default: { get: vi.fn() } }));

beforeEach(() => {
  apiClient.get.mockReset();
  resetFoodMatchMemo();
});

describe('matchFoods — the server decides which food a query names', () => {
  it('keeps the server\'s suggestions and intent as sent; drops malformed entries', () => {
    expect(normalizeFoodMatch({
      foods: [{ key: 'pho', label: 'Phở' }, { key: 'x' }, null],
      intent: { key: 'pho', label: 'Phở', keywords: ['phở'] },
    })).toEqual({ foods: [{ key: 'pho', label: 'Phở' }], intent: { key: 'pho', label: 'Phở' } });
    expect(normalizeFoodMatch({ foods: [] })).toEqual({ foods: [], intent: null });
    expect(normalizeFoodMatch(null)).toEqual({ foods: [], intent: null });
  });

  it('asks once per query; a failure is not remembered', async () => {
    apiClient.get.mockRejectedValueOnce(Object.assign(new Error('boom'), { response: { status: 500, headers: {}, data: {} } }));
    await expect(matchFoods(' phở ')).rejects.toMatchObject({ status: 500 });
    apiClient.get.mockResolvedValue({ data: { foods: [{ key: 'pho', label: 'Phở' }], intent: { key: 'pho', label: 'Phở' } } });
    await matchFoods('phở');
    await matchFoods('phở');
    expect(apiClient.get).toHaveBeenCalledTimes(2);
    expect(apiClient.get).toHaveBeenLastCalledWith('/catalog/foods/match', { params: { q: 'phở' } });
    await expect(matchFoods('   ')).resolves.toEqual({ foods: [], intent: null });
    expect(apiClient.get).toHaveBeenCalledTimes(2);
  });
});
