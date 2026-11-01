import { StrictMode } from 'react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { act, fireEvent, render, screen } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import apiClient from '../api/client';
import HomePage from './HomePage';
import { resetLocationScope } from '../hooks/useLocationScope';
import { resetAvailableFoodsCache } from '../hooks/useAvailableFoods';
import { resetCatalogCache } from '../hooks/useCatalog';
import { resetFoodDecisionMemory } from '../hooks/useFoodDecisionSource';
import { cityFoods, userFoods } from '../test/foodFixtures';
import { SUSHI, USER_SCOPE, foodDecision } from '../test/foodDecisionFixtures';

vi.mock('../api/client', () => ({ default: { get: vi.fn() } }));
// The homepage must never mount the map. If anything imported it, this would throw.
vi.mock('../components/map/MapView', () => ({ default: () => { throw new Error('MapView mounted on the homepage'); } }));

const USER = { lat: 10.7725, lng: 106.698 };
const advance = (ms) => act(() => vi.advanceTimersByTimeAsync(ms));

beforeEach(() => {
  vi.useFakeTimers();
  apiClient.get.mockReset();
  apiClient.get.mockImplementation(async (url, config) => {
    if (url === '/decisions/foods/available') return { data: config.params.lat != null ? userFoods() : cityFoods() };
    if (url === '/decisions/food') return { data: foodDecision(SUSHI, { scope: USER_SCOPE }) };
    throw new Error(`unexpected request ${url}`);
  });
  resetLocationScope();
  resetAvailableFoodsCache();
  resetCatalogCache();
  resetFoodDecisionMemory();
});
afterEach(() => {
  vi.useRealTimers();
  vi.unstubAllGlobals();
});

const renderHome = () => render(<StrictMode><MemoryRouter><HomePage /></MemoryRouter></StrictMode>);
const urls = () => apiClient.get.mock.calls.map(([url]) => url);

describe('Homepage network behaviour', () => {
  it('on load: exactly one available-foods request (even in StrictMode), no catalog, no map, no location prompt', async () => {
    const geo = vi.fn();
    vi.stubGlobal('navigator', { ...navigator, geolocation: { getCurrentPosition: geo, watchPosition: vi.fn() } });
    renderHome();
    await advance(5000);

    expect(urls()).toEqual(['/decisions/foods/available']);
    expect(urls().some((u) => /viewport|nearby|catalog/.test(u))).toBe(false);
    expect(geo).not.toHaveBeenCalled();
    expect(screen.getByRole('link', { name: 'Phở, 3 quán quanh trung tâm TP.HCM' })).toBeInTheDocument();
    expect(screen.queryByText('Chọn một món để tìm trên bản đồ')).not.toBeInTheDocument();   // old CategoryStrip is gone
  });

  it('a location granted through the cà mèn is reused by "Thèm gì?" — no second prompt', async () => {
    const geo = vi.fn((ok) => ok({ coords: { latitude: USER.lat, longitude: USER.lng } }));
    vi.stubGlobal('navigator', {
      ...navigator,
      geolocation: { getCurrentPosition: geo, watchPosition: vi.fn() },
      permissions: { query: vi.fn(async () => ({ state: 'prompt' })) },
    });
    renderHome();
    await advance(100);

    fireEvent.click(screen.getByRole('button', { name: 'Mở cà mèn' }));
    await advance(3000);

    expect(geo).toHaveBeenCalledTimes(1);
    expect(urls().filter((u) => u === '/decisions/food')).toHaveLength(1);   // the cà mèn asks for a FOOD
    expect(urls()).not.toContain('/decisions/today');
    const available = apiClient.get.mock.calls.filter(([u]) => u === '/decisions/foods/available').map(([, c]) => c.params);
    expect(available).toEqual([{ kinds: 'MEAL,DRINK,DESSERT' }, { lat: USER.lat, lng: USER.lng, kinds: 'MEAL,DRINK,DESSERT' }]);
    expect(screen.getByRole('radio', { name: 'Gần tôi' })).toBeChecked();
    expect(screen.getByRole('link', { name: 'Sushi, 4 quán trong 3 km gần bạn' })).toHaveAttribute('href', '/mon/sushi?near=1');
  });
});
