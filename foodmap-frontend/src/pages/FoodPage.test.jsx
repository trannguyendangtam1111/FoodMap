import { StrictMode } from 'react';
import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { act, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { MemoryRouter, Route, Routes, useLocation } from 'react-router-dom';
import apiClient from '../api/client';
import FoodPage from './FoodPage';
import HomePage from './HomePage';
import HeroSection from '../components/home/HeroSection';
import { getLocationState, rememberLocation, resetLocationScope } from '../hooks/useLocationScope';
import { resetCatalogCache } from '../hooks/useCatalog';
import { resetAvailableFoodsCache } from '../hooks/useAvailableFoods';
import { resetFoodDecisionMemory } from '../hooks/useFoodDecisionSource';
import { resetFoodRestaurantsMemory } from '../hooks/useFoodRestaurants';
import { catalogBody, cityFoods, httpError } from '../test/foodFixtures';
import { PHO, foodDecision } from '../test/foodDecisionFixtures';
import {
  AREA_SCOPE, NHA_HANG_NGON, PHO_27, PHO_HOA, USER_SCOPE,
  byMenu, byName, foodRestaurants, manyPho, noSuggestion, suggestion,
} from '../test/foodRestaurantFixtures';

vi.mock('../api/client', () => ({ default: { get: vi.fn() } }));
// The food page must never mount the map. If anything imported it, this would throw.
vi.mock('../components/map/MapView', () => ({ default: () => { throw new Error('MapView mounted'); } }));

const USER = { lat: 10.7725, lng: 106.698 };
const LIST = '/decisions/foods/pho/restaurants';
const THREE = [byName(PHO_HOA), byMenu(NHA_HANG_NGON, 'Phở Bò Tái'), byName(PHO_27)];
const WIDENED_CITY = {
  locationSource: 'CITY_CENTER', area: null, radiusMeters: 5000, radiusWidened: true, label: 'Quanh trung tâm TP.HCM',
};

let handlers;
let geo;

function Where() {
  const { pathname, search } = useLocation();
  return <p data-testid="where">{pathname + search}</p>;
}

const renderAt = (url, { strict = false } = {}) => {
  const tree = (
    <MemoryRouter initialEntries={[url]}>
      <Routes>
        <Route path="/mon/:foodKey" element={<><FoodPage /><Where /></>} />
        <Route path="/restaurants/:id" element={<Where />} />
        <Route path="/" element={<Where />} />
      </Routes>
    </MemoryRouter>
  );
  return render(strict ? <StrictMode>{tree}</StrictMode> : tree);
};

const calls = (url) => apiClient.get.mock.calls.filter(([u]) => u === url).map(([, c]) => c?.params ?? {});
const urls = () => apiClient.get.mock.calls.map(([u]) => u);
const listRegion = () => screen.getByRole('list', { name: /^Quán phở/ });
const rowNames = () => within(listRegion()).getAllByRole('link').map((a) => a.querySelector('p').textContent);
const action = () => document.querySelector('[data-suggestion-action]');
const listSection = () => screen.getByRole('region', { name: /^Quán phở/ });

function stubGeolocation(mode) {
  geo = vi.fn((ok, fail) => {
    if (mode === 'allow') ok({ coords: { latitude: USER.lat, longitude: USER.lng } });
    else fail({ code: 1, message: 'User denied Geolocation' });
  });
  vi.stubGlobal('navigator', {
    ...navigator,
    geolocation: { getCurrentPosition: geo, watchPosition: vi.fn() },
    permissions: { query: vi.fn(async () => ({ state: 'prompt' })) },
  });
}

beforeEach(() => {
  handlers = {
    '/catalog': () => catalogBody(),
    '/decisions/today': () => suggestion(PHO_HOA),
    [LIST]: () => foodRestaurants(THREE),
    '/decisions/foods/available': () => cityFoods(),
    '/decisions/food': () => foodDecision(PHO),
  };
  apiClient.get.mockReset();
  apiClient.get.mockImplementation(async (url, config) => {
    const handler = handlers[url];
    if (!handler) throw new Error(`unexpected request ${url}`);
    return { data: await handler(config?.params ?? {}) };
  });
  resetCatalogCache();
  resetLocationScope();
  resetAvailableFoodsCache();
  resetFoodDecisionMemory();
  resetFoodRestaurantsMemory();
  stubGeolocation('allow');
});
afterEach(() => {
  vi.useRealTimers();
  vi.unstubAllGlobals();
});

describe('/mon/:foodKey — the food first', () => {
  it('names the server\'s food: an h1, FoodArt keyed by the food, the scope', async () => {
    const { container } = renderAt('/mon/pho');
    expect(await screen.findByRole('heading', { level: 1, name: 'Phở' })).toBeInTheDocument();
    const art = container.querySelector('[data-food-art]');
    expect(art).toHaveAttribute('data-food-art', 'pho');
    expect(art).toHaveAttribute('aria-hidden', 'true');
    expect(await screen.findByRole('heading', { level: 2, name: 'Quán phở quanh trung tâm TP.HCM' })).toBeInTheDocument();
    expect(screen.getByRole('heading', { level: 2, name: 'Gợi ý cho bạn' })).toBeInTheDocument();
  });

  it.each(['khong-co', 'tra'])('unknown or classification-only key "%s": "Chưa có món này" — no restaurant request, no other food', async (key) => {
    renderAt(`/mon/${key}`);
    expect(await screen.findByRole('heading', { name: 'Chưa có món này trên FoodMap.' })).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Về trang chủ' })).toHaveAttribute('href', '/');
    expect(urls()).toEqual(['/catalog']);
  });

  it('catalog failure: an error with "Thử lại" that works', async () => {
    handlers['/catalog'] = vi.fn().mockRejectedValueOnce(httpError(500)).mockImplementation(() => catalogBody());
    renderAt('/mon/pho');
    expect(await screen.findByRole('heading', { name: 'Chưa tải được danh sách món.' })).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: 'Thử lại' }));
    expect(await screen.findByRole('heading', { level: 1, name: 'Phở' })).toBeInTheDocument();
  });
});

describe('restaurants — the server\'s, as sent', () => {
  it('the list is exactly the server\'s items in the server\'s order, each with its evidence', async () => {
    renderAt('/mon/pho');
    await screen.findByRole('list', { name: /^Quán phở/ });
    expect(rowNames()).toEqual(['Phở Hòa Pasteur', 'Nhà Hàng Ngon', 'Phở']);
    const rows = within(listRegion()).getAllByRole('link');
    // classified by the server: a menu match whose name says nothing about phở is listed, with why
    expect(within(rows[1]).getByText('Thực đơn có Phở Bò Tái')).toBeInTheDocument();
    expect(within(rows[0]).getByText('Tên quán cho thấy có phở')).toBeInTheDocument();
    expect(screen.getByText(/^3 quán · Số quán FoodMap biết có món này/)).toBeInTheDocument();
    // a rating only with reviews; nothing invented for the unrated row
    expect(rows[2].textContent).not.toMatch(/0[,.]0|Chưa có đánh giá/);
  });

  it('nothing is filtered or re-ordered locally: a different server order is shown as is', async () => {
    handlers[LIST] = () => foodRestaurants([byName(PHO_27), byMenu(NHA_HANG_NGON, 'Phở Bò Tái')]);
    renderAt('/mon/pho');
    await screen.findByRole('list', { name: /^Quán phở/ });
    expect(rowNames()).toEqual(['Phở', 'Nhà Hàng Ngon']);
  });

  it('the suggestion is the server\'s draw, with the server\'s reasons and category', async () => {
    renderAt('/mon/pho');
    const card = await screen.findByRole('article', { name: 'Phở Hòa Pasteur' });
    expect(within(card).getByText('Tên quán cho thấy có phở')).toBeInTheDocument();
    expect(within(card).getByText('Bún, phở, mì')).toBeInTheDocument();      // the server's category, as a label
    expect(card.textContent).not.toMatch(/Bạn chưa chia sẻ vị trí/);         // the page states its scope itself
  });

  it('every restaurant leads to /restaurants/:id', async () => {
    renderAt('/mon/pho');
    await screen.findByRole('list', { name: /^Quán phở/ });
    expect(within(listRegion()).getAllByRole('link').map((a) => a.getAttribute('href')))
      .toEqual(['/restaurants/1', '/restaurants/9', '/restaurants/27']);
    expect(within(screen.getByRole('article')).getByRole('link', { name: /Xem quán/ })).toHaveAttribute('href', '/restaurants/1');
    fireEvent.click(within(listRegion()).getAllByRole('link')[1]);
    expect(screen.getByTestId('where')).toHaveTextContent(/^\/restaurants\/9$/);
  });

  it('"Quán khác" excludes every restaurant already suggested and keeps focus', async () => {
    handlers['/decisions/today'] = vi.fn()
      .mockResolvedValueOnce(suggestion(PHO_HOA))
      .mockResolvedValueOnce(suggestion(NHA_HANG_NGON))
      .mockResolvedValueOnce(suggestion(PHO_27));
    renderAt('/mon/pho');
    await screen.findByRole('article', { name: 'Phở Hòa Pasteur' });

    action().focus();
    fireEvent.click(action());
    await screen.findByRole('article', { name: 'Nhà Hàng Ngon' });
    await waitFor(() => expect(action()).toHaveTextContent('Quán khác'));
    expect(action()).toHaveFocus();
    fireEvent.click(action());
    await screen.findByRole('article', { name: 'Phở' });

    expect(calls('/decisions/today').map((p) => p.exclude)).toEqual([undefined, '1', '1,9']);
    expect(screen.getByText('Gợi ý: Phở.')).toBeInTheDocument();             // announced
    expect(calls(LIST)).toHaveLength(1);                                       // the list is not refetched
  });

  it('every restaurant shown: an honest end, then "Xem lại từ đầu" starts over', async () => {
    handlers['/decisions/today'] = vi.fn()
      .mockResolvedValueOnce(suggestion(PHO_HOA))
      .mockResolvedValueOnce(noSuggestion('ALL_SHOWN'))
      .mockResolvedValueOnce(suggestion(PHO_HOA));
    renderAt('/mon/pho');
    await screen.findByRole('article', { name: 'Phở Hòa Pasteur' });
    action().focus();
    fireEvent.click(action());
    expect(await screen.findAllByText('Bạn đã xem hết 1 quán phở quanh trung tâm TP.HCM.')).not.toHaveLength(0);
    expect(action()).toHaveTextContent('Xem lại từ đầu');
    expect(action()).toHaveFocus();
    fireEvent.click(action());
    await screen.findByRole('article', { name: 'Phở Hòa Pasteur' });
    expect(calls('/decisions/today')[2]).not.toHaveProperty('exclude');
  });

  it('"Xem thêm quán" appends the server\'s next page and moves focus to the first new restaurant', async () => {
    const all = manyPho(14);
    handlers[LIST] = ({ page }) => foodRestaurants(page === 0 ? all.slice(0, 12) : all.slice(12), { total: 14, page });
    renderAt('/mon/pho');
    await screen.findByRole('list', { name: /^Quán phở/ });
    expect(within(listRegion()).getAllByRole('link')).toHaveLength(12);

    fireEvent.click(screen.getByRole('button', { name: 'Xem thêm quán' }));
    await waitFor(() => expect(within(listRegion()).getAllByRole('link')).toHaveLength(14));
    expect(calls(LIST).map((p) => p.page)).toEqual([0, 1]);
    expect(calls(LIST).every((p) => p.size === 12)).toBe(true);
    expect(within(listRegion()).getAllByRole('link')[12]).toHaveFocus();
    expect(screen.queryByRole('button', { name: 'Xem thêm quán' })).toBeNull();
  });

  it('a widened radius is said, with the radius', async () => {
    handlers[LIST] = () => foodRestaurants(THREE, { scope: WIDENED_CITY });
    renderAt('/mon/pho');
    expect(await screen.findByText('Quanh trung tâm TP.HCM ít quán phở, FoodMap đã tìm rộng hơn, trong 5 km.')).toBeInTheDocument();
  });
});

describe('scope — USER, AREA, CITY_CENTER; never coordinates in a URL', () => {
  it('CITY_CENTER: no location parameter at all', async () => {
    renderAt('/mon/pho');
    await screen.findByRole('list', { name: /^Quán phở/ });
    expect(calls('/decisions/today')).toEqual([{ food: 'pho' }]);
    expect(calls(LIST)).toEqual([{ page: 0, size: 12 }]);
  });

  it('AREA: the area key only — no centre, no distance; the store follows the URL', async () => {
    handlers['/decisions/today'] = () => suggestion(PHO_27, { locationSource: 'AREA', area: { key: 'binh-thanh', label: 'Bình Thạnh' } });
    handlers[LIST] = () => foodRestaurants([byName(PHO_27)], { scope: AREA_SCOPE });
    renderAt('/mon/pho?area=binh-thanh');
    await screen.findByRole('heading', { level: 2, name: 'Quán phở quanh Bình Thạnh' });
    expect(calls('/decisions/today')).toEqual([{ area: 'binh-thanh', food: 'pho' }]);
    expect(calls(LIST)).toEqual([{ area: 'binh-thanh', page: 0, size: 12 }]);
    expect(document.body.textContent).not.toMatch(/Cách bạn|Gần bạn/);
    expect(getLocationState().scope).toMatchObject({ mode: 'AREA', areaKey: 'binh-thanh' });
  });

  it('USER (location already granted in this tab): lat and lng to the API, real distances, ?near=1 in the URL', async () => {
    rememberLocation(USER);
    handlers['/decisions/today'] = () => suggestion(PHO_HOA, { locationSource: 'USER', distanceMeters: 450, fallbackLevel: 0 });
    handlers[LIST] = () => foodRestaurants([{ ...byName(PHO_HOA), distanceMeters: 450 }], { scope: USER_SCOPE });
    renderAt('/mon/pho?near=1');
    await screen.findByRole('heading', { level: 2, name: 'Quán phở gần bạn' });
    expect(calls('/decisions/today')).toEqual([{ lat: USER.lat, lng: USER.lng, food: 'pho' }]);
    expect(calls(LIST)).toEqual([{ lat: USER.lat, lng: USER.lng, page: 0, size: 12 }]);
    expect(within(listRegion()).getByText('Cách bạn', { exact: false })).toBeInTheDocument();
    expect(geo).not.toHaveBeenCalled();
    expect(screen.getByTestId('where').textContent).toBe('/mon/pho?near=1');
  });

  it('AREA / CITY_CENTER never show a distance, even if one were sent', async () => {
    handlers[LIST] = () => foodRestaurants([{ ...byName(PHO_HOA), distanceMeters: 450 }]);
    renderAt('/mon/pho');
    await screen.findByRole('list', { name: /^Quán phở/ });
    expect(listRegion().textContent).not.toMatch(/Cách bạn|450 m/);
  });

  it('?near=1 without a location: asks where — never the browser on load; "Gần tôi" asks once', async () => {
    renderAt('/mon/pho?near=1');
    expect(await screen.findByRole('heading', { name: 'Bạn muốn tìm quán phở ở đâu?' })).toBeInTheDocument();
    expect(geo).not.toHaveBeenCalled();
    expect(urls()).toEqual(['/catalog']);

    fireEvent.click(screen.getByRole('button', { name: 'Gần tôi' }));
    await screen.findByRole('list', { name: /^Quán phở/ });
    expect(geo).toHaveBeenCalledTimes(1);
    expect(calls(LIST)[0]).toMatchObject({ lat: USER.lat, lng: USER.lng });
    expect(screen.getByTestId('where').textContent).toBe('/mon/pho?near=1');
    expect(screen.getByRole('radio', { name: 'Gần tôi' })).toHaveFocus();    // focus kept, not dropped
  });

  it('"Gần tôi" denied: stays on the question, says so, offers areas', async () => {
    stubGeolocation('deny');
    renderAt('/mon/pho?near=1');
    fireEvent.click(await screen.findByRole('button', { name: 'Gần tôi' }));
    expect(await screen.findByText('Không lấy được vị trí. Bạn có thể chọn khu vực.')).toBeInTheDocument();
    expect(screen.getByRole('heading', { name: 'Bạn muốn tìm quán phở ở đâu?' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Chọn khu vực' })).toBeInTheDocument();
    expect(calls(LIST)).toHaveLength(0);
  });

  it('choosing an area on the page: ?area= in the URL, the area key to the API, the store updated', async () => {
    renderAt('/mon/pho');
    await screen.findByRole('list', { name: /^Quán phở/ });
    fireEvent.click(screen.getByRole('radio', { name: 'Khu vực' }));
    fireEvent.click(await screen.findByRole('button', { name: 'Quận 1' }));
    await waitFor(() => expect(calls(LIST)).toHaveLength(2));
    expect(calls(LIST)[1]).toEqual({ area: 'quan-1', page: 0, size: 12 });
    expect(screen.getByTestId('where').textContent).toBe('/mon/pho?area=quan-1');
    expect(getLocationState().scope).toMatchObject({ mode: 'AREA', areaKey: 'quan-1' });
    expect(screen.getByRole('button', { name: 'Đổi khu vực' })).toHaveFocus();
  });

  it('an area the catalog does not offer is said so — nothing requested, nothing guessed', async () => {
    renderAt('/mon/pho?area=quan-99');
    expect(await screen.findByText('Khu vực này chưa có trên FoodMap.', { selector: '.text-body' })).toBeInTheDocument();
    expect(urls()).toEqual(['/catalog']);
  });

  it('no URL ever carries coordinates', async () => {
    renderAt('/mon/pho?near=1');
    fireEvent.click(await screen.findByRole('button', { name: 'Gần tôi' }));
    await screen.findByRole('list', { name: /^Quán phở/ });
    // FoodMap's own URLs: no coordinates at all. "Chỉ đường" (external, Google
    // Maps) carries exactly one thing: the restaurant's destination — never the user's.
    const links = [...document.querySelectorAll('a')];
    const hrefs = links.filter((a) => !a.hasAttribute('data-directions')).map((a) => a.getAttribute('href'));
    for (const url of [screen.getByTestId('where').textContent, ...hrefs]) {
      expect(url).not.toMatch(/lat|lng|10\.77|106\.69/);
    }
    const directions = links.filter((a) => a.hasAttribute('data-directions')).map((a) => new URL(a.getAttribute('href')));
    expect(directions).toHaveLength(1);
    for (const url of directions) {
      expect([...url.searchParams.keys()]).toEqual(['api', 'destination']);
      expect(url.searchParams.get('destination')).toBe(`${PHO_HOA.latitude},${PHO_HOA.longitude}`);
      expect(url.href).not.toContain(String(USER.lat));
      expect(url.href).not.toContain(String(USER.lng));
    }
  });
});

describe('empty, error and rate-limit states', () => {
  it('a known food with no restaurant in scope: says so, never another food', async () => {
    handlers['/decisions/today'] = () => noSuggestion('NO_FOOD_IN_SCOPE');
    handlers[LIST] = () => foodRestaurants([], { scope: WIDENED_CITY });
    renderAt('/mon/pho');
    expect(await screen.findByText('Chưa có quán phở quanh trung tâm TP.HCM.', { selector: '.text-body' })).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Món khác' })).toHaveAttribute('href', '/');
    expect(screen.getByRole('button', { name: 'Khu vực khác' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Gần tôi' })).toBeInTheDocument();
    expect(screen.queryByRole('article')).toBeNull();
  });

  it('a list error: friendly, with a retry that works', async () => {
    handlers[LIST] = vi.fn().mockRejectedValueOnce(httpError(500)).mockImplementation(() => foodRestaurants(THREE));
    renderAt('/mon/pho');
    expect(await screen.findByText('Chưa tải được danh sách quán.', { selector: 'p.font-semibold' })).toBeInTheDocument();
    expect(screen.getByText('FoodMap đang gặp chút trục trặc, bạn thử lại nhé.')).toBeInTheDocument();
    fireEvent.click(within(listSection()).getByRole('button', { name: 'Thử lại' }));
    await screen.findByRole('list', { name: /^Quán phở/ });
    expect(rowNames()).toHaveLength(3);
  });

  it('429: the server\'s Retry-After is counted down; retry is held until then', async () => {
    handlers['/decisions/today'] = () => { throw httpError(429, { headers: { 'retry-after': '7' } }); };
    handlers[LIST] = () => { throw httpError(429, { headers: { 'retry-after': '7' } }); };
    renderAt('/mon/pho');
    expect(await screen.findAllByText('Bạn bấm hơi nhanh, đợi 7 giây rồi thử lại nhé.')).not.toHaveLength(0);
    expect(action()).toHaveTextContent('Thử lại');
    expect(action()).toHaveAttribute('aria-disabled', 'true');
    expect(within(listSection()).getByRole('button', { name: 'Thử lại' })).toBeDisabled();
  });

  it('a slow suggestion times out (5 s) with a friendly retry', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true });
    handlers['/decisions/today'] = () => new Promise(() => {});
    renderAt('/mon/pho');
    await screen.findByRole('list', { name: /^Quán phở/ });     // the suggestion request is now waiting
    await act(() => vi.advanceTimersByTimeAsync(5100));
    expect(screen.getAllByText('FoodMap phản hồi hơi lâu, bạn thử lại nhé.').length).toBeGreaterThan(0);
    expect(action()).toHaveTextContent('Thử lại');
  });
});

describe('memory, StrictMode, safety', () => {
  it('back from a restaurant: the same suggestion and list — no request, no reveal, no announcement', async () => {
    const first = renderAt('/mon/pho');
    await screen.findByRole('article', { name: 'Phở Hòa Pasteur' });
    await screen.findByRole('list', { name: /^Quán phở/ });
    const before = apiClient.get.mock.calls.length;
    first.unmount();

    renderAt('/mon/pho');
    const card = await screen.findByRole('article', { name: 'Phở Hòa Pasteur' });
    expect(card).not.toHaveClass('fm-reveal');
    expect(rowNames()).toEqual(['Phở Hòa Pasteur', 'Nhà Hàng Ngon', 'Phở']);
    expect(screen.queryByText(/^Gợi ý: /)).toBeNull();
    await new Promise((r) => setTimeout(r, 20));
    expect(apiClient.get.mock.calls.length).toBe(before);
  });

  it('StrictMode: one request per endpoint', async () => {
    renderAt('/mon/pho', { strict: true });
    await screen.findByRole('article', { name: 'Phở Hòa Pasteur' });
    await screen.findByRole('list', { name: /^Quán phở/ });
    await new Promise((r) => setTimeout(r, 20));
    expect(urls().sort()).toEqual(['/catalog', '/decisions/today', LIST].sort());
  });

  it('read-only discovery only: no map, no viewport/nearby, no provider, no other fetch', async () => {
    const fetchSpy = vi.fn();
    vi.stubGlobal('fetch', fetchSpy);
    renderAt('/mon/pho');
    await screen.findByRole('list', { name: /^Quán phở/ });
    await screen.findByRole('article');
    fireEvent.click(action());
    await waitFor(() => expect(calls('/decisions/today')).toHaveLength(2));
    expect(new Set(urls())).toEqual(new Set(['/catalog', '/decisions/today', LIST]));
    expect(urls().some((u) => /viewport|nearby|goong|map/i.test(u))).toBe(false);
    expect(fetchSpy).not.toHaveBeenCalled();
  });
});

describe('one canonical food page', () => {
  it('the router has exactly one /mon/:foodKey route, and it is FoodPage', () => {
    const router = readFileSync(resolve(process.cwd(), 'src/router/AppRouter.jsx'), 'utf8');
    expect(router.match(/path="\/mon\//g)).toHaveLength(1);
    expect(router).toMatch(/<Route path="\/mon\/:foodKey" element={<FoodPage \/>} \/>/);
  });

  it('"Thèm gì?" → Phở → /mon/pho, the same page with the same key', async () => {
    render(
      <MemoryRouter initialEntries={['/']}>
        <Routes>
          <Route path="/" element={<HomePage />} />
          <Route path="/mon/:foodKey" element={<><FoodPage /><Where /></>} />
        </Routes>
      </MemoryRouter>,
    );
    fireEvent.click(await screen.findByRole('link', { name: 'Phở, 3 quán quanh trung tâm TP.HCM' }));
    expect(await screen.findByRole('heading', { level: 1, name: 'Phở' })).toBeInTheDocument();
    expect(screen.getByTestId('where').textContent).toBe('/mon/pho');
    await screen.findByRole('list', { name: /^Quán phở/ });
    expect(calls(LIST)).toHaveLength(1);
  });

  it('cà mèn → Phở → "Tìm quán phở" → /mon/pho, the same page with the same key', async () => {
    vi.useFakeTimers();
    stubGeolocation('deny');
    render(
      <MemoryRouter initialEntries={['/']}>
        <Routes>
          <Route path="/" element={<HeroSection />} />
          <Route path="/mon/:foodKey" element={<><FoodPage /><Where /></>} />
        </Routes>
      </MemoryRouter>,
    );
    fireEvent.click(screen.getByRole('button', { name: 'Mở cà mèn' }));
    await act(() => vi.advanceTimersByTimeAsync(3000));
    fireEvent.click(screen.getByRole('link', { name: 'Tìm quán phở' }));
    await act(() => vi.advanceTimersByTimeAsync(100));
    expect(screen.getByRole('heading', { level: 1, name: 'Phở' })).toBeInTheDocument();
    expect(screen.getByTestId('where').textContent).toBe('/mon/pho');
    expect(calls(LIST)).toHaveLength(1);
  });
});

describe('count semantics — the list says "at least" only when the server does', () => {
  it('an exact total: "127 quán"', async () => {
    handlers[LIST] = () => foodRestaurants(manyPho(12), { total: 127 });
    renderAt('/mon/pho');
    expect(await screen.findByText(/^127 quán · Số quán FoodMap biết/)).toBeInTheDocument();
    expect(screen.getByText('127 quán phở quanh trung tâm TP.HCM.')).toBeInTheDocument();
  });

  it('a capped total (totalCapped): "2000+ quán"; paging continues within the evaluated rows', async () => {
    handlers[LIST] = () => ({ ...foodRestaurants(manyPho(12), { total: 2000 }), totalCapped: true });
    renderAt('/mon/pho');
    expect(await screen.findByText(/^2000\+ quán · Số quán FoodMap biết/)).toBeInTheDocument();
    expect(screen.getByText('2000+ quán phở quanh trung tâm TP.HCM.')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Xem thêm quán' })).toBeInTheDocument();
  });

  it('nothing is inferred from the number: 2000 exact is "2000 quán", 7 capped is "7+ quán"', async () => {
    handlers[LIST] = () => foodRestaurants(manyPho(12), { total: 2000 });
    const first = renderAt('/mon/pho');
    expect(await screen.findByText(/^2000 quán · /)).toBeInTheDocument();
    first.unmount();

    resetFoodRestaurantsMemory();
    handlers[LIST] = () => ({ ...foodRestaurants(manyPho(7)), totalCapped: true });
    renderAt('/mon/pho');
    expect(await screen.findByText(/^7\+ quán · /)).toBeInTheDocument();
  });

  it('a capped read with none for the food does not claim there are none at all', async () => {
    handlers['/decisions/today'] = () => noSuggestion('NO_FOOD_IN_SCOPE');
    handlers[LIST] = () => ({ ...foodRestaurants([]), totalCapped: true });
    renderAt('/mon/pho');
    expect(await screen.findByText('Trong các quán FoodMap đã xem quanh trung tâm TP.HCM, chưa thấy quán phở.', { selector: '.text-body' }))
      .toBeInTheDocument();
    expect(screen.queryByText(/^Chưa có quán phở/)).toBeNull();
  });
});

describe('"Chỉ đường" — on the suggestion, to the restaurant\'s own coordinates', () => {
  const directions = (name) => screen.queryByRole('link', { name: `Chỉ đường đến ${name}` });

  it('the suggestion card offers directions beside "Xem quán"; the list rows do not; no extra request', async () => {
    renderAt('/mon/pho');
    await screen.findByRole('list', { name: /^Quán phở/ });
    const link = await screen.findByRole('link', { name: 'Chỉ đường đến Phở Hòa Pasteur' });
    expect(link).toHaveAttribute('href', 'https://www.google.com/maps/dir/?api=1&destination=10.7762%2C106.6892');
    expect(link).toHaveAttribute('target', '_blank');
    expect(screen.getByRole('link', { name: 'Xem quán Phở Hòa Pasteur' })).toHaveAttribute('href', '/restaurants/1');
    expect(within(listRegion()).queryByRole('link', { name: /^Chỉ đường/ })).toBeNull();
    expect([...new Set(urls())].sort()).toEqual(['/catalog', '/decisions/today', LIST].sort());
  });

  it('USER scope: the URL holds the restaurant\'s coordinates only — never the user\'s; no location request', async () => {
    rememberLocation(USER);
    handlers['/decisions/today'] = () => suggestion(PHO_HOA, { locationSource: 'USER', distanceMeters: 450, fallbackLevel: 0 });
    handlers[LIST] = () => foodRestaurants([{ ...byName(PHO_HOA), distanceMeters: 450 }], { scope: USER_SCOPE });
    renderAt('/mon/pho?near=1');
    const href = (await screen.findByRole('link', { name: 'Chỉ đường đến Phở Hòa Pasteur' })).getAttribute('href');
    expect(new URL(href).searchParams.get('destination')).toBe('10.7762,106.6892');
    expect(href).not.toContain(String(USER.lat));
    expect(href).not.toContain(String(USER.lng));
    expect(new URL(href).searchParams.has('origin')).toBe(false);
    expect(geo).not.toHaveBeenCalled();
    expect(screen.getByTestId('where').textContent).toBe('/mon/pho?near=1');   // FoodMap's own URL unchanged
  });

  it.each([
    ['without latitude', { latitude: undefined }],
    ['without longitude', { longitude: undefined }],
    ['with an invalid latitude', { latitude: 95 }],
    ['with an invalid longitude', { longitude: 190 }],
  ])('a suggestion %s: no "Chỉ đường", the card is otherwise unchanged', async (_, broken) => {
    handlers['/decisions/today'] = () => suggestion({ ...PHO_HOA, ...broken });
    renderAt('/mon/pho');
    expect(await screen.findByRole('link', { name: 'Xem quán Phở Hòa Pasteur' })).toBeInTheDocument();
    expect(directions('Phở Hòa Pasteur')).toBeNull();
  });
});

describe('restaurant links carry the food (for "Quán này có" on the detail page)', () => {
  function StateProbe() {
    const { pathname, state } = useLocation();
    return <p data-testid="arrived">{`${pathname} ${JSON.stringify(state)}`}</p>;
  }
  const renderWithProbe = () => render(
    <MemoryRouter initialEntries={['/mon/pho']}>
      <Routes>
        <Route path="/mon/:foodKey" element={<FoodPage />} />
        <Route path="/restaurants/:id" element={<StateProbe />} />
      </Routes>
    </MemoryRouter>,
  );

  it('a list row → the detail page, with { fromFood: "pho" } as router state (not in the URL)', async () => {
    renderWithProbe();
    await screen.findByRole('list', { name: /^Quán phở/ });
    fireEvent.click(within(listRegion()).getAllByRole('link')[1]);
    expect(screen.getByTestId('arrived').textContent).toBe('/restaurants/9 {"fromFood":"pho"}');
  });

  it('the suggestion\'s "Xem quán" → the same state', async () => {
    renderWithProbe();
    fireEvent.click(await screen.findByRole('link', { name: 'Xem quán Phở Hòa Pasteur' }));
    expect(screen.getByTestId('arrived').textContent).toBe('/restaurants/1 {"fromFood":"pho"}');
  });
});
