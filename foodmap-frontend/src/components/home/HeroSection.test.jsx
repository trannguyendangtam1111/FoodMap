import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { act, fireEvent, render, screen, within } from '@testing-library/react';
import { MemoryRouter, Route, Routes, useLocation } from 'react-router-dom';
import apiClient from '../../api/client';
import HeroSection from './HeroSection';
import { TIMING } from '../../hooks/useTiffinDecision';
import { resetFoodDecisionMemory } from '../../hooks/useFoodDecisionSource';
import { rememberLocation, resetLocationScope, selectArea, selectCityCentre } from '../../hooks/useLocationScope';
import { AREA_SCOPE, LAU, PHO, SUSHI, USER_SCOPE, foodDecision, noFood } from '../../test/foodDecisionFixtures';

// The real adapter, scope store and location flow, with only the HTTP client
// and the browser geolocation faked.
vi.mock('../../api/client', () => ({ default: { get: vi.fn() } }));

const USER = { lat: 10.7725, lng: 106.698 };
const SLACK = 2000; // generous: location + latency + animation
const advance = (ms) => act(() => vi.advanceTimersByTimeAsync(ms));

function stubGeolocation(mode) {
  const getCurrentPosition = vi.fn((ok, fail) => {
    if (mode === 'allow') ok({ coords: { latitude: USER.lat, longitude: USER.lng } });
    else fail({ code: 1, message: 'User denied Geolocation' });
  });
  const watchPosition = vi.fn();
  vi.stubGlobal('navigator', {
    ...navigator,
    geolocation: { getCurrentPosition, watchPosition },
    permissions: { query: vi.fn(async () => ({ state: 'prompt' })) },
  });
  return { getCurrentPosition, watchPosition };
}

function answer(...bodies) {
  bodies.forEach((body) => apiClient.get.mockResolvedValueOnce({ data: body }));
}

function Where() {
  const { pathname, search } = useLocation();
  return <p data-testid="where">{pathname + search}</p>;
}

const renderHero = () => render(
  <MemoryRouter>
    <Routes>
      <Route path="/" element={<HeroSection />} />
      <Route path="/mon/:foodKey" element={<Where />} />
    </Routes>
  </MemoryRouter>,
);
const openBox = () => fireEvent.click(screen.getByRole('button', { name: 'Mở cà mèn' }));
const reroll = () => fireEvent.click(screen.getByRole('button', { name: 'Món khác' }));
const params = (n) => apiClient.get.mock.calls[n][1].params;

beforeEach(() => {
  vi.useFakeTimers();
  apiClient.get.mockReset();
  resetFoodDecisionMemory(); // the session is module-level (survives in-app navigation)
  resetLocationScope();
});
afterEach(() => {
  vi.useRealTimers();
  vi.unstubAllGlobals();
});

describe('Homepage cà mèn — food-first, real API wiring', () => {
  it('asks for nothing on mount: no geolocation, no request', async () => {
    const geo = stubGeolocation('allow');
    renderHero();
    await advance(5000);
    expect(geo.getCurrentPosition).not.toHaveBeenCalled();
    expect(geo.watchPosition).not.toHaveBeenCalled();
    expect(apiClient.get).not.toHaveBeenCalled();
  });

  it('on press: asks for location, then GET /decisions/food with lat and lng (no radius) — the server picks the food', async () => {
    const geo = stubGeolocation('allow');
    answer(foodDecision(SUSHI, { scope: USER_SCOPE, restaurantCount: 4, radiusMeters: 3000, radiusWidened: true }));
    renderHero();

    openBox();
    await advance(SLACK);
    expect(geo.getCurrentPosition).toHaveBeenCalledTimes(1);
    expect(apiClient.get).toHaveBeenCalledTimes(1);
    expect(apiClient.get.mock.calls[0][0]).toBe('/decisions/food');
    expect(params(0)).toEqual({ lat: USER.lat, lng: USER.lng });
    expect(screen.getByRole('heading', { name: 'Sushi' })).toBeInTheDocument();
    expect(within(screen.getByRole('article')).getByText('4 quán trong 3 km gần bạn')).toBeInTheDocument();
  });

  it('denied location: the city centre, said as such — never "near you"', async () => {
    stubGeolocation('deny');
    answer(foodDecision(PHO));
    renderHero();

    openBox();
    await advance(SLACK);
    expect(params(0)).toEqual({});   // no lat, lng or radius
    const card = screen.getByRole('article');
    expect(card.textContent).not.toMatch(/gần bạn|Cách bạn/i);
    expect(within(card).getByText('3 quán quanh trung tâm TP.HCM')).toBeInTheDocument();
  });

  it('"Món khác" sends the foods already shown as exclude, without asking for location again', async () => {
    const geo = stubGeolocation('allow');
    answer(foodDecision(PHO, { scope: USER_SCOPE }), foodDecision(SUSHI, { scope: USER_SCOPE }), foodDecision(LAU, { scope: USER_SCOPE }));
    renderHero();

    openBox();
    await advance(SLACK);
    reroll();
    await advance(SLACK);
    reroll();
    await advance(SLACK);

    expect(params(0)).not.toHaveProperty('exclude');
    expect(params(1)).toEqual({ exclude: 'pho', lat: USER.lat, lng: USER.lng });
    expect(params(2)).toEqual({ exclude: 'pho,sushi', lat: USER.lat, lng: USER.lng });
    expect(geo.getCurrentPosition).toHaveBeenCalledTimes(1);
    expect(screen.getByRole('heading', { name: 'Lẩu' })).toBeInTheDocument();
  });

  it('an area chosen in "Thèm gì?" is used as is — no location prompt, only the area key is sent', async () => {
    const geo = stubGeolocation('allow');
    selectArea('binh-thanh', 'Bình Thạnh');
    answer(foodDecision(LAU, { scope: AREA_SCOPE, restaurantCount: 2, radiusMeters: 2500 }));
    renderHero();

    openBox();
    await advance(SLACK);
    expect(geo.getCurrentPosition).not.toHaveBeenCalled();
    expect(params(0)).toEqual({ area: 'binh-thanh' });
    expect(screen.getByRole('link', { name: 'Tìm quán lẩu' })).toHaveAttribute('href', '/mon/lau?area=binh-thanh');
  });

  it('a location already granted in this tab is reused; an explicit city-centre choice is respected', async () => {
    const geo = stubGeolocation('allow');
    rememberLocation(USER);
    answer(foodDecision(PHO, { scope: USER_SCOPE }));
    const first = renderHero();
    openBox();
    await advance(SLACK);
    expect(params(0)).toEqual({ lat: USER.lat, lng: USER.lng });
    first.unmount();

    resetFoodDecisionMemory();
    selectCityCentre();               // the user picked "Trung tâm"
    answer(foodDecision(PHO));
    renderHero();
    openBox();
    await advance(SLACK);
    expect(params(1)).toEqual({});
    expect(geo.getCurrentPosition).not.toHaveBeenCalled();
  });

  it('"Tìm quán {món}" opens /mon/{key} — navigation only, no coordinates, no restaurant decision', async () => {
    stubGeolocation('allow');
    answer(foodDecision(PHO, { scope: USER_SCOPE }));
    renderHero();
    openBox();
    await advance(SLACK);

    const link = screen.getByRole('link', { name: 'Tìm quán phở' });
    expect(link).toHaveAttribute('href', '/mon/pho?near=1');
    fireEvent.click(link);
    expect(screen.getByTestId('where')).toHaveTextContent(/^\/mon\/pho\?near=1$/);
    expect(screen.getByTestId('where').textContent).not.toMatch(/lat|lng|10\.77|106\.69/);
    expect(apiClient.get.mock.calls.map((c) => c[0])).toEqual(['/decisions/food']);
  });

  it('every food already shown: an honest empty state and "Xem lại từ đầu"', async () => {
    stubGeolocation('deny');
    answer(foodDecision(PHO), noFood('ALL_SHOWN'), foodDecision(PHO));
    renderHero();
    openBox();
    await advance(SLACK);
    reroll();
    await advance(SLACK);

    expect(screen.getAllByText('Bạn đã xem hết các món quanh trung tâm TP.HCM.').length).toBeGreaterThan(0);
    fireEvent.click(screen.getByRole('button', { name: 'Xem lại từ đầu' }));
    await advance(SLACK);
    expect(params(2)).toEqual({});
    expect(screen.getByRole('heading', { name: 'Phở' })).toBeInTheDocument();
  });

  it('resizing or rotating the device never requests a decision or location', async () => {
    const geo = stubGeolocation('allow');
    answer(foodDecision(PHO));
    renderHero();

    const shake = async () => {
      for (const type of ['resize', 'orientationchange']) window.dispatchEvent(new Event(type));
      await advance(1000);
    };
    await shake();
    expect(apiClient.get).not.toHaveBeenCalled();
    expect(geo.getCurrentPosition).not.toHaveBeenCalled();

    openBox();
    await advance(SLACK);
    await shake(); // after a reveal, too
    expect(apiClient.get).toHaveBeenCalledTimes(1);
    expect(geo.getCurrentPosition).toHaveBeenCalledTimes(1);
  });

  it('on the first reveal, focuses the food name and brings the actions clear of the bottom nav', async () => {
    stubGeolocation('deny');
    answer(foodDecision(PHO));
    const scrolled = [];
    const original = Element.prototype.scrollIntoView;
    Element.prototype.scrollIntoView = function scrollIntoView(options) { scrolled.push([this, options]); };
    try {
      renderHero();
      openBox();
      await advance(SLACK);

      expect(screen.getByRole('heading', { name: 'Phở' })).toHaveFocus();
      // the whole actions row — on phones "Tìm quán phở" stacks below "Món khác"
      const more = screen.getByRole('button', { name: 'Món khác' });
      const row = more.parentElement;
      expect(row).toContainElement(screen.getByRole('link', { name: 'Tìm quán phở' }));
      expect(row).toHaveClass('scroll-mb-bottom-nav');
      expect(scrolled).toContainEqual([row, expect.objectContaining({ block: 'nearest' })]);
    } finally {
      Element.prototype.scrollIntoView = original;
    }
  });

  it('coming back from the food page shows the same food — no new request, no re-prompt', async () => {
    const geo = stubGeolocation('allow');
    answer(foodDecision(PHO, { scope: USER_SCOPE }), foodDecision(SUSHI, { scope: USER_SCOPE }));
    const first = renderHero();
    openBox();
    await advance(SLACK);
    expect(screen.getByRole('heading', { name: 'Phở' })).toBeInTheDocument();
    first.unmount(); // "Tìm quán phở" → food page

    renderHero(); // browser Back
    const card = screen.getByRole('article', { name: 'Phở' });
    expect(card).not.toHaveClass('fm-reveal'); // shown as it was, not re-revealed
    expect(document.activeElement).toBe(document.body); // no focus jump on return
    expect(screen.getByRole('status')).toHaveTextContent(''); // nothing re-announced
    expect(apiClient.get).toHaveBeenCalledTimes(1);

    reroll(); // the session continues: same exclude list, location reused
    await advance(SLACK);
    expect(params(1)).toEqual({ exclude: 'pho', lat: USER.lat, lng: USER.lng });
    expect(geo.getCurrentPosition).toHaveBeenCalledTimes(1);
    expect(screen.getByRole('heading', { name: 'Sushi' })).toBeInTheDocument();
  });

  it('only ever calls the food decision endpoint — never the restaurant decision, map or provider', async () => {
    stubGeolocation('allow');
    const fetchSpy = vi.fn();
    vi.stubGlobal('fetch', fetchSpy);
    answer(foodDecision(PHO, { scope: USER_SCOPE }), foodDecision(SUSHI, { scope: USER_SCOPE }));
    renderHero();

    openBox();
    await advance(SLACK);
    reroll();
    await advance(TIMING.timeout);

    expect(apiClient.get.mock.calls.map((c) => c[0])).toEqual(['/decisions/food', '/decisions/food']);
    expect(fetchSpy).not.toHaveBeenCalled();
  });
});
