import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { act, fireEvent, render, screen, within } from '@testing-library/react';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import apiClient from '../../api/client';
import HeroSection from '../home/HeroSection';
import HomePage from '../../pages/HomePage';
import { TIMING } from '../../hooks/useTiffinDecision';
import { resetFoodDecisionMemory } from '../../hooks/useFoodDecisionSource';
import { resetLocationScope } from '../../hooks/useLocationScope';
import { resetAvailableFoodsCache } from '../../hooks/useAvailableFoods';
import { resetCatalogCache } from '../../hooks/useCatalog';
import { resetDecisionMode } from '../../hooks/useDecisionMode';
import { ROLL_TIMING, rollStripFoods } from './RollDecision';
import { cityFoods } from '../../test/foodFixtures';
import { LAU, PHO, SUSHI, foodDecision } from '../../test/foodDecisionFixtures';

// The real panel, presentations, decision hook, source, scope store and
// availability cache — only the HTTP client and the browser location are faked.
vi.mock('../../api/client', () => ({ default: { get: vi.fn() } }));
vi.mock('../map/MapView', () => ({ default: () => { throw new Error('MapView mounted on the homepage'); } }));

const SLACK = 3000;   // location + latency + either presentation's steps
// One act per ~frame, as a browser renders while time passes (a single large
// act would defer every render to its end).
const advance = async (ms) => {
  for (let left = ms; left > 0; left -= 16) {
    // eslint-disable-next-line no-await-in-loop
    await act(() => vi.advanceTimersByTimeAsync(Math.min(16, left)));
  }
};
let decisions;
let geo;

beforeEach(() => {
  vi.useFakeTimers();
  decisions = [];
  apiClient.get.mockReset();
  apiClient.get.mockImplementation(async (url) => {
    if (url === '/decisions/food') return { data: decisions.shift() };
    if (url === '/decisions/foods/available') return { data: cityFoods() };
    throw new Error(`unexpected request ${url}`);
  });
  geo = vi.fn((ok, fail) => fail({ code: 1, message: 'User denied Geolocation' }));
  vi.stubGlobal('navigator', {
    ...navigator,
    geolocation: { getCurrentPosition: geo, watchPosition: vi.fn() },
    permissions: { query: vi.fn(async () => ({ state: 'prompt' })) },
  });
  resetFoodDecisionMemory();
  resetLocationScope();
  resetAvailableFoodsCache();
  resetCatalogCache();
  resetDecisionMode();
});
afterEach(() => {
  vi.useRealTimers();
  vi.unstubAllGlobals();
});

const renderHero = () => render(
  <MemoryRouter>
    <Routes><Route path="/" element={<HeroSection />} /></Routes>
  </MemoryRouter>,
);
const radio = (name) => screen.getByRole('radio', { name });
const toRoll = () => fireEvent.click(radio('Roll'));
const toTiffin = () => fireEvent.click(radio('Cà mèn'));
const panels = () => document.querySelectorAll('section#an-gi');
const urls = (u) => apiClient.get.mock.calls.filter(([url]) => url === u);
const params = (u) => urls(u).map(([, c]) => c.params);

describe('DecisionPanel — mode', () => {
  it('starts on Cà mèn; exactly one presentation is mounted', () => {
    renderHero();
    expect(screen.getByRole('group', { name: 'Cách chọn món' })).toBeInTheDocument();
    expect(radio('Cà mèn')).toBeChecked();
    expect(radio('Roll')).not.toBeChecked();
    expect(screen.getByRole('button', { name: 'Mở cà mèn' })).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Chạy Roll' })).toBeNull();
    expect(panels()).toHaveLength(1);
    expect(screen.getAllByRole('status')).toHaveLength(1);
  });

  it('switches Cà mèn → Roll → Cà mèn, mounting one presentation at a time', async () => {
    renderHero();
    toRoll();
    expect(radio('Roll')).toBeChecked();
    expect(screen.getByRole('button', { name: 'Chạy Roll' })).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Mở cà mèn' })).toBeNull();
    expect(panels()).toHaveLength(1);
    expect(panels()[0]).toHaveAttribute('data-decision-mode', 'roll');

    toTiffin();
    expect(screen.getByRole('button', { name: 'Mở cà mèn' })).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Chạy Roll' })).toBeNull();
    expect(panels()).toHaveLength(1);
    expect(panels()[0]).not.toHaveAttribute('data-decision-mode');
  });

  it.each([['Cà mèn', 'Mở cà mèn'], ['Roll', 'Chạy Roll']])('the switch is disabled while %s is deciding', async (mode, action) => {
    decisions.push(foodDecision(PHO));
    renderHero();
    fireEvent.click(radio(mode));
    fireEvent.click(screen.getByRole('button', { name: action }));
    await advance(150);
    expect(radio('Cà mèn')).toBeDisabled();
    expect(radio('Roll')).toBeDisabled();
    // a click on the disabled radio changes nothing: the mode and the presentation stay
    fireEvent.click(radio(mode === 'Roll' ? 'Cà mèn' : 'Roll'));
    expect(radio(mode)).toBeChecked();
    expect(panels()).toHaveLength(1);
    expect(panels()[0].dataset.decisionMode ?? 'tiffin').toBe(mode === 'Roll' ? 'roll' : 'tiffin');

    await advance(SLACK);
    expect(screen.getByRole('heading', { name: 'Phở' })).toBeInTheDocument();
    expect(radio('Cà mèn')).toBeEnabled();
    expect(radio('Roll')).toBeEnabled();
  });

  it('the mode survives in-app navigation (memory only)', () => {
    const first = renderHero();
    toRoll();
    first.unmount();
    renderHero();
    expect(radio('Roll')).toBeChecked();
    expect(screen.getByRole('button', { name: 'Chạy Roll' })).toBeInTheDocument();
  });
});

describe('DecisionPanel — one shared session', () => {
  it('the current food and the exclusions carry across switches, in both directions', async () => {
    decisions.push(foodDecision(PHO), foodDecision(SUSHI), foodDecision(LAU));
    renderHero();
    fireEvent.click(screen.getByRole('button', { name: 'Mở cà mèn' }));
    await advance(SLACK);
    expect(screen.getByRole('heading', { name: 'Phở' })).toBeInTheDocument();

    toRoll();   // same food, shown as it was: no new request, no reveal, nothing announced
    const card = screen.getByRole('article', { name: 'Phở' });
    expect(card).not.toHaveClass('fm-reveal');
    expect(screen.getByRole('status')).toHaveTextContent('');
    expect(urls('/decisions/food')).toHaveLength(1);

    fireEvent.click(screen.getByRole('button', { name: 'Chạy lại' }));
    await advance(SLACK);
    expect(screen.getByRole('heading', { name: 'Sushi' })).toBeInTheDocument();
    expect(params('/decisions/food')[1]).toEqual({ exclude: 'pho' });

    toTiffin();
    expect(screen.getByRole('heading', { name: 'Sushi' })).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: 'Món khác' }));
    await advance(SLACK);
    expect(params('/decisions/food')[2]).toEqual({ exclude: 'pho,sushi' });
    expect(screen.getByRole('heading', { name: 'Lẩu' })).toBeInTheDocument();
  });
});

describe('RollDecision — the same decision', () => {
  it('uses GET /decisions/food (never a restaurant decision) and the cà mèn\'s location rule', async () => {
    decisions.push(foodDecision(PHO), foodDecision(SUSHI));
    renderHero();
    await advance(10);
    expect(geo).not.toHaveBeenCalled();                    // nothing on load
    toRoll();
    fireEvent.click(screen.getByRole('button', { name: 'Chạy Roll' }));
    await advance(SLACK);
    expect(geo).toHaveBeenCalledTimes(1);                  // first run, nothing chosen, no location yet
    fireEvent.click(screen.getByRole('button', { name: 'Chạy lại' }));
    await advance(SLACK);
    expect(geo).toHaveBeenCalledTimes(1);                  // "Chạy lại" never asks
    expect(params('/decisions/food')).toEqual([{}, { exclude: 'pho' }]);   // denied → city centre, no coordinates
    expect(urls('/decisions/today')).toHaveLength(0);
  });

  it('the server\'s food is the result, even one the strip never showed', async () => {
    decisions.push(foodDecision(LAU));                     // lẩu is not among the strip's foods
    renderHero();
    toRoll();
    await advance(10);
    expect(document.querySelector('[data-roll-strip]').dataset.foods.split(' ')).not.toContain('lau');
    fireEvent.click(screen.getByRole('button', { name: 'Chạy Roll' }));
    await advance(SLACK);
    expect(screen.getByRole('heading', { name: 'Lẩu' })).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Tìm quán lẩu' })).toHaveAttribute('href', '/mon/lau');
    expect(screen.getByRole('status')).toHaveTextContent('Gợi ý: Lẩu.');
  });

  it('Roll runs on its own timing; the cà mèn\'s is unchanged', async () => {
    expect(TIMING).toEqual({ press: 140, shake: 720, rerollShake: 420, open: 400, timeout: 5000 });
    decisions.push(foodDecision(PHO));
    renderHero();
    toRoll();
    fireEvent.click(screen.getByRole('button', { name: 'Chạy Roll' }));
    await advance(ROLL_TIMING.press + ROLL_TIMING.shake + ROLL_TIMING.open - 50);
    expect(screen.queryByRole('heading', { name: 'Phở' })).toBeNull();      // not before Roll's steps
    await advance(100);
    expect(screen.getByRole('heading', { name: 'Phở' })).toBeInTheDocument();
  });

  it('empty and error states use the shared semantics, in Roll\'s words', async () => {
    decisions.push({
      decisionId: null, food: null, restaurantCount: 0, radiusMeters: 5000, radiusWidened: true,
      scope: { locationSource: 'CITY_CENTER', area: null, radiusMeters: 3000, label: 'Quanh trung tâm TP.HCM' },
      candidateFoodCount: 0, emptyReason: 'NO_FOOD_IN_SCOPE',
    });
    renderHero();
    toRoll();
    fireEvent.click(screen.getByRole('button', { name: 'Chạy Roll' }));
    await advance(SLACK);
    expect(screen.getAllByText('Chưa có món nào FoodMap biết quanh trung tâm TP.HCM.').length).toBeGreaterThan(0);
    expect(screen.getByRole('button', { name: 'Thử lại' })).toBeInTheDocument();

    apiClient.get.mockImplementation(async (url) => {
      if (url === '/decisions/food') throw Object.assign(new Error('boom'), { response: { status: 503, headers: {}, data: {} } });
      return { data: cityFoods() };
    });
    fireEvent.click(screen.getByRole('button', { name: 'Thử lại' }));
    await advance(SLACK);
    expect(screen.getAllByText('Roll chưa chạy được. Thử lại nhé.').length).toBeGreaterThan(0);
  });
});

describe('RollDecision — the strip\'s foods', () => {
  it('rollStripFoods: MEAL only, the server\'s order, minus the foods already shown — nothing drawn', () => {
    const foods = cityFoods().foods;
    expect(rollStripFoods(foods).map((f) => f.key)).toEqual(['bun', 'pho', 'pizza', 'sushi']);
    expect(rollStripFoods(foods, ['pho', 'sushi']).map((f) => f.key)).toEqual(['bun', 'pizza']);
    expect(rollStripFoods(foods, ['pho'])).toEqual(rollStripFoods(foods, ['pho']));   // deterministic
    expect(rollStripFoods(null)).toEqual([]);
  });

  it('the static strip shows the available MEAL foods of the scope, decorative only', async () => {
    renderHero();
    toRoll();
    await advance(10);
    const strip = document.querySelector('[data-roll-strip]');
    expect(strip).toHaveAttribute('aria-hidden', 'true');
    expect(strip.dataset.foods).toBe('bun pho pizza sushi');
    // the loop: whole repetitions of that list, in that order — nothing reordered, none doubled
    const tiles = [...strip.querySelectorAll('[data-food-art]')].map((e) => e.dataset.foodArt);
    expect(tiles.length % 4).toBe(0);
    tiles.forEach((key, i) => expect(key).toBe(['bun', 'pho', 'pizza', 'sushi'][i % 4]));
    expect(strip.textContent).toBe('');                    // no labels, no odds
    expect(params('/decisions/foods/available')).toEqual([{ kinds: 'MEAL,DRINK,DESSERT' }]);
  });

  it('reuses the "Thèm gì?" availability already loaded for the scope — no second request', async () => {
    render(<MemoryRouter><HomePage /></MemoryRouter>);
    await advance(100);
    expect(urls('/decisions/foods/available')).toHaveLength(1);
    toRoll();
    await advance(100);
    expect(document.querySelector('[data-roll-strip]').dataset.foods).toBe('bun pho pizza sushi');
    expect(urls('/decisions/foods/available')).toHaveLength(1);
    expect(apiClient.get.mock.calls.map(([u]) => u).some((u) => /viewport|nearby|map/.test(u))).toBe(false);
  });
});

describe('the homepage stays safe', () => {
  it('switching to Roll on load asks for nothing but the cached availability: no decision, no location, no map', async () => {
    render(<MemoryRouter><HomePage /></MemoryRouter>);
    await advance(100);
    toRoll();
    toTiffin();
    toRoll();
    await advance(1000);
    expect(urls('/decisions/food')).toHaveLength(0);
    expect(geo).not.toHaveBeenCalled();
    expect(within(document.body).queryByText(/MapView/)).toBeNull();
  });
});
