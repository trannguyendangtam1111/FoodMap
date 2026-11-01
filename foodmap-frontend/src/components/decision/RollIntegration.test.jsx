import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { act, fireEvent, render, screen, within } from '@testing-library/react';
import { Link, MemoryRouter, Route, Routes, useLocation, useNavigate } from 'react-router-dom';
import apiClient from '../../api/client';
import HeroSection from '../home/HeroSection';
import { resetFoodDecisionMemory } from '../../hooks/useFoodDecisionSource';
import { rememberLocation, resetLocationScope, selectArea } from '../../hooks/useLocationScope';
import { resetAvailableFoodsCache } from '../../hooks/useAvailableFoods';
import { resetDecisionMode, setDecisionMode } from '../../hooks/useDecisionMode';
import { ROLL_TIMING } from './RollDecision';
import { cityFoods } from '../../test/foodFixtures';
import { AREA_SCOPE, PHO, SUSHI, USER_SCOPE, foodDecision, noFood } from '../../test/foodDecisionFixtures';

// Roll R4 — integration: navigation, scopes, errors and cancellation through
// the real panel, hook, source, scope store and reel. Only HTTP and the
// browser location are faked; the map must never be imported.
vi.mock('../../api/client', () => ({ default: { get: vi.fn() } }));
vi.mock('../map/MapView', () => ({ default: () => { throw new Error('MapView mounted'); } }));

const USER = { lat: 10.7725, lng: 106.698 };
const FIRST = ROLL_TIMING.press + ROLL_TIMING.shake + ROLL_TIMING.open;
const AGAIN = ROLL_TIMING.rerollShake + ROLL_TIMING.rerollOpen;
// One act per ~frame, as a browser renders while time passes.
const advance = async (ms) => {
  for (let left = ms; left > 0; left -= 16) {
    // eslint-disable-next-line no-await-in-loop
    await act(() => vi.advanceTimersByTimeAsync(Math.min(16, left)));
  }
};
let decisions;
let geo;

function httpError(status, headers = {}) {
  return Object.assign(new Error(`HTTP ${status}`), { response: { status, headers, data: {} } });
}

beforeEach(() => {
  vi.useFakeTimers();
  decisions = [];
  apiClient.get.mockReset();
  apiClient.get.mockImplementation(async (url, config) => {
    if (url === '/decisions/food') {
      const next = decisions.shift();
      if (next instanceof Error) throw next;
      return { data: typeof next === 'function' ? await next(config) : next };
    }
    if (url === '/decisions/foods/available') return { data: cityFoods() };
    throw new Error(`unexpected request ${url}`);
  });
  geo = vi.fn((ok, fail) => fail({ code: 1 }));
  vi.stubGlobal('navigator', {
    ...navigator,
    geolocation: { getCurrentPosition: geo, watchPosition: vi.fn() },
    permissions: { query: vi.fn(async () => ({ state: 'prompt' })) },
  });
  resetFoodDecisionMemory();
  resetLocationScope();
  resetAvailableFoodsCache();
  resetDecisionMode();
  setDecisionMode('roll');
});
afterEach(() => {
  vi.useRealTimers();
  vi.unstubAllGlobals();
});

function FoodPageStub() {
  const { pathname, search } = useLocation();
  const navigate = useNavigate();
  return (
    <>
      <p data-testid="where">{pathname + search}</p>
      <button type="button" onClick={() => navigate(-1)}>Quay lại</button>
    </>
  );
}

const renderApp = () => render(
  <MemoryRouter initialEntries={['/']}>
    <Link to={{ pathname: '/', hash: '#an-gi' }}>Ăn gì?</Link>
    <Routes>
      <Route path="/" element={<HeroSection />} />
      <Route path="/mon/:foodKey" element={<FoodPageStub />} />
    </Routes>
  </MemoryRouter>,
);
const decisionCalls = () => apiClient.get.mock.calls.filter(([u]) => u === '/decisions/food');
const params = () => decisionCalls().map(([, c]) => c.params);
const reel = () => document.querySelector('[data-roll-strip]');
const runRoll = () => fireEvent.click(screen.getByRole('button', { name: 'Chạy Roll' }));
const runAgain = () => fireEvent.click(screen.getByRole('button', { name: 'Chạy lại' }));
const hint = () => document.getElementById('decision-hint').textContent;

describe('R4 — result → food page → Back', () => {
  it('"Tìm quán" opens the server\'s food page with no second decision; Back restores Roll as it was', async () => {
    decisions.push(foodDecision(PHO));
    renderApp();
    await advance(20);
    runRoll();
    await advance(FIRST + 30);
    expect(screen.getByRole('heading', { name: 'Phở' })).toBeInTheDocument();

    fireEvent.click(screen.getByRole('link', { name: 'Tìm quán phở' }));
    expect(screen.getByTestId('where').textContent).toBe('/mon/pho');              // the exact server food
    await advance(500);
    expect(decisionCalls()).toHaveLength(1);                                         // no second decision

    fireEvent.click(screen.getByRole('button', { name: 'Quay lại' }));              // browser Back
    await advance(100);
    expect(screen.getByRole('radio', { name: 'Roll' })).toBeChecked();             // Roll still selected
    const card = screen.getByRole('article', { name: 'Phở' });                      // same food
    expect(card).not.toHaveClass('fm-reveal');                                      // no replayed reveal
    expect(reel().dataset.reelState).not.toBe('rolling');                           // no replayed roll
    expect(reel().dataset.centredKey).toBe('pho');
    expect(within(document.getElementById('an-gi')).getByRole('status')).toHaveTextContent('');   // nothing re-announced
    expect(document.activeElement).toBe(document.body);                             // no focus jump
    await advance(3000);
    expect(decisionCalls()).toHaveLength(1);                                         // no new request

    runAgain();                                                                      // and Roll carries on
    decisions.push(foodDecision(SUSHI));
    await advance(AGAIN + 40);
    expect(params()[1]).toEqual({ exclude: 'pho' });
  });
});

describe('R4 — "Ăn gì?" (bottom navigation)', () => {
  it('lands on the Roll panel and focuses its action — no decision, no location, no map', async () => {
    renderApp();
    await advance(20);
    fireEvent.click(screen.getByRole('link', { name: 'Ăn gì?' }));
    await advance(20);
    expect(screen.getByRole('button', { name: 'Chạy Roll' })).toHaveFocus();
    expect(decisionCalls()).toHaveLength(0);
    expect(geo).not.toHaveBeenCalled();
  });
});

describe('R4 — scope carried to the food page, never coordinates', () => {
  it('AREA: the area key to the API; ?area= on the link; no user distance', async () => {
    selectArea('binh-thanh', 'Bình Thạnh');
    decisions.push(foodDecision(PHO, { scope: AREA_SCOPE, restaurantCount: 2, radiusMeters: 2500 }));
    renderApp();
    await advance(20);
    runRoll();
    await advance(FIRST + 30);
    expect(geo).not.toHaveBeenCalled();
    expect(params()).toEqual([{ area: 'binh-thanh' }]);
    expect(screen.getByRole('link', { name: 'Tìm quán phở' })).toHaveAttribute('href', '/mon/pho?area=binh-thanh');
    expect(document.getElementById('an-gi').textContent).not.toMatch(/gần bạn|Cách bạn/i);
  });

  it('USER (location already in this tab): lat/lng to the API only; ?near=1 on the link', async () => {
    rememberLocation(USER);
    decisions.push(foodDecision(PHO, { scope: USER_SCOPE }));
    renderApp();
    await advance(20);
    runRoll();
    await advance(FIRST + 30);
    expect(geo).not.toHaveBeenCalled();
    expect(params()).toEqual([{ lat: USER.lat, lng: USER.lng }]);
    const link = screen.getByRole('link', { name: 'Tìm quán phở' });
    expect(link).toHaveAttribute('href', '/mon/pho?near=1');
    fireEvent.click(link);
    expect(screen.getByTestId('where').textContent).not.toMatch(/lat|lng|10\.77|106\.69/);
  });

  it('CITY_CENTER: no location parameters; a plain /mon/{key}', async () => {
    decisions.push(foodDecision(PHO));
    renderApp();
    await advance(20);
    runRoll();
    await advance(FIRST + 30);
    expect(params()).toEqual([{}]);
    expect(screen.getByRole('link', { name: 'Tìm quán phở' })).toHaveAttribute('href', '/mon/pho');
  });
});

describe('R4 — errors, in Roll\'s words', () => {
  it('429: the server\'s Retry-After is counted down; the button is held until then', async () => {
    decisions.push(httpError(429, { 'retry-after': '3' }), foodDecision(PHO));
    renderApp();
    await advance(20);
    runRoll();
    await advance(ROLL_TIMING.press + 50);                                        // a failure is not held back by the roll
    expect(hint()).toBe('Bạn bấm nhanh quá, đợi 3 giây nhé.');
    expect(['stopping', 'rest', 'idle']).toContain(reel().dataset.reelState);
    const retry = screen.getByRole('button', { name: 'Thử lại' });
    expect(retry).toHaveAttribute('aria-disabled', 'true');
    fireEvent.click(retry);
    await advance(100);
    expect(decisionCalls()).toHaveLength(1);                                         // held: no request
    await advance(3000);
    expect(hint()).toBe('Giờ bạn thử lại được rồi.');
    fireEvent.click(screen.getByRole('button', { name: 'Thử lại' }));
    await advance(FIRST + 30);
    expect(screen.getByRole('heading', { name: 'Phở' })).toBeInTheDocument();
  });

  it('network failure and 400 on a first run: the friendly error and "Thử lại"', async () => {
    decisions.push(new Error('Network Error'), httpError(400));
    renderApp();
    await advance(20);
    runRoll();
    await advance(FIRST);
    expect(hint()).toBe('Roll chưa chạy được. Thử lại nhé.');
    expect(['rest', 'idle']).toContain(reel().dataset.reelState);                   // no food in the frame
    fireEvent.click(screen.getByRole('button', { name: 'Thử lại' }));
    await advance(FIRST);
    expect(hint()).toBe('Roll chưa chạy được. Thử lại nhé.');
  });

  it('400 after a result (the exclusion list itself): "Xem lại từ đầu" starts a clean session', async () => {
    decisions.push(foodDecision(PHO), httpError(400), foodDecision(SUSHI));
    renderApp();
    await advance(20);
    runRoll();
    await advance(FIRST + 30);
    runAgain();
    await advance(AGAIN + 40);
    expect(screen.getAllByText('Roll chưa chạy được. Thử lại nhé.').length).toBeGreaterThan(0);
    fireEvent.click(screen.getByRole('button', { name: 'Xem lại từ đầu' }));
    await advance(AGAIN + 40);
    expect(params()[2]).toEqual({});                                                 // exclusions cleared
    expect(screen.getByRole('heading', { name: 'Sushi' })).toBeInTheDocument();
  });

  it('ALL_SHOWN: an honest end and "Xem lại từ đầu"', async () => {
    decisions.push(foodDecision(PHO), noFood('ALL_SHOWN'), foodDecision(PHO));
    renderApp();
    await advance(20);
    runRoll();
    await advance(FIRST + 30);
    runAgain();
    await advance(AGAIN + 40);
    expect(screen.getAllByText('Bạn đã xem hết các món quanh trung tâm TP.HCM.').length).toBeGreaterThan(0);
    fireEvent.click(screen.getByRole('button', { name: 'Xem lại từ đầu' }));
    await advance(AGAIN + 40);
    expect(params()[2]).toEqual({});
    expect(screen.getByRole('heading', { name: 'Phở' })).toBeInTheDocument();
  });
});

describe('R4 — cancellation', () => {
  it('a mode change mid-run (not possible from the UI) cancels the run: request aborted, nothing revealed later', async () => {
    let signal;
    decisions.push((config) => {
      ({ signal } = config);
      return new Promise((resolve) => setTimeout(() => resolve(foodDecision(PHO)), 1500));
    });
    renderApp();
    await advance(20);
    runRoll();
    await advance(400);
    expect(reel().dataset.reelState).toBe('rolling');
    act(() => setDecisionMode('tiffin'));
    await advance(5000);
    expect(signal.aborted).toBe(true);
    expect(screen.getByRole('button', { name: 'Mở cà mèn' })).toBeInTheDocument();  // the cà mèn, untouched
    expect(screen.queryByRole('heading', { name: 'Phở' })).toBeNull();               // the old answer never shows
    act(() => setDecisionMode('roll'));
    await advance(100);
    expect(screen.getByRole('button', { name: 'Chạy Roll' })).toBeInTheDocument();
    expect(screen.queryByRole('heading', { name: 'Phở' })).toBeNull();
  });
});
