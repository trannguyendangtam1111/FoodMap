import { readFileSync } from 'node:fs';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { act, fireEvent, render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter, Route, Routes, useLocation } from 'react-router-dom';
import apiClient from '../../api/client';
import ThemGiSection from './ThemGiSection';
import { rememberLocation, resetLocationScope } from '../../hooks/useLocationScope';
import { resetAvailableFoodsCache } from '../../hooks/useAvailableFoods';
import { resetCatalogCache } from '../../hooks/useCatalog';
import {
  binhThanhFoods, catalogBody, cityFoods, emptyFoods, httpError, networkError, userFoods,
} from '../../test/foodFixtures';

// The real section, hooks, store and API module; only the HTTP client and
// the browser geolocation are faked.
vi.mock('../../api/client', () => ({ default: { get: vi.fn() } }));

const USER = { lat: 10.772512, lng: 106.698049 };
const AVAILABLE = '/decisions/foods/available';

/** routes: url → (params) => body | throws */
function serve(routes) {
  apiClient.get.mockImplementation(async (url, config) => {
    const handler = routes[url];
    if (!handler) throw new Error(`unexpected request ${url}`);
    return { data: await handler(config?.params ?? {}) };
  });
}

const byScope = ({ city = cityFoods, user = userFoods, area = binhThanhFoods } = {}) => (params) => {
  if (params.lat != null) return typeof user === 'function' ? user(params) : user;
  if (params.area) return typeof area === 'function' ? area(params) : area;
  return typeof city === 'function' ? city(params) : city;
};

const availableCalls = () => apiClient.get.mock.calls.filter(([url]) => url === AVAILABLE).map(([, c]) => c.params);
const catalogCalls = () => apiClient.get.mock.calls.filter(([url]) => url === '/catalog');

function stubGeolocation(mode) {
  const getCurrentPosition = vi.fn((ok, fail) => {
    if (mode === 'allow') ok({ coords: { latitude: USER.lat, longitude: USER.lng } });
    else fail({ code: 1, message: 'User denied Geolocation' });
  });
  vi.stubGlobal('navigator', {
    ...navigator,
    geolocation: mode === 'missing' ? undefined : { getCurrentPosition, watchPosition: vi.fn() },
    permissions: { query: vi.fn(async () => ({ state: 'prompt' })) },
  });
  return getCurrentPosition;
}

function Where() {
  const { pathname, search } = useLocation();
  return <p data-testid="where">{pathname + search}</p>;
}

const renderSection = () => render(
  <MemoryRouter initialEntries={['/']}>
    <Routes>
      <Route path="/" element={<ThemGiSection />} />
      <Route path="/mon/:foodKey" element={<Where />} />
    </Routes>
  </MemoryRouter>,
);

const radio = (name) => screen.getByRole('radio', { name });
const chipNames = () => within(screen.getByRole('list', { name: /Các món có quán/ }))
  .getAllByRole('link').map((a) => a.getAttribute('aria-label'));

beforeEach(() => {
  apiClient.get.mockReset();
  resetLocationScope();
  resetAvailableFoodsCache();
  resetCatalogCache();
});
afterEach(() => {
  vi.useRealTimers();
  vi.unstubAllGlobals();
});

// ── Scope ─────────────────────────────────────────────────────────────────────

describe('"Thèm gì?" — scope', () => {
  it('defaults to the city centre: one request, no location params, no prompt, no catalog', async () => {
    const geo = stubGeolocation('allow');
    serve({ [AVAILABLE]: byScope() });
    renderSection();

    expect(await screen.findByRole('link', { name: 'Phở, 3 quán quanh trung tâm TP.HCM' })).toBeInTheDocument();
    expect(availableCalls()).toEqual([{ kinds: 'MEAL,DRINK,DESSERT' }]);
    expect(radio('Trung tâm')).toBeChecked();
    expect(screen.getByText('Quanh trung tâm TP.HCM')).toBeInTheDocument();
    expect(geo).not.toHaveBeenCalled();
    expect(catalogCalls()).toHaveLength(0);
  });

  it('uses USER when this tab already holds a granted location — without asking again', async () => {
    const geo = stubGeolocation('allow');
    rememberLocation(USER);            // e.g. granted earlier through the cà mèn
    serve({ [AVAILABLE]: byScope() });
    renderSection();

    expect(await screen.findByRole('link', { name: 'Sushi, 4 quán trong 3 km gần bạn' })).toBeInTheDocument();
    expect(availableCalls()).toEqual([{ lat: USER.lat, lng: USER.lng, kinds: 'MEAL,DRINK,DESSERT' }]);
    expect(radio('Gần tôi')).toBeChecked();
    expect(screen.getByText('Gần bạn')).toBeInTheDocument();
    expect(geo).not.toHaveBeenCalled();
  });

  it('"Gần tôi" asks for the location only when pressed, then reloads with lat/lng', async () => {
    const geo = stubGeolocation('allow');
    serve({ [AVAILABLE]: byScope() });
    renderSection();
    await screen.findByRole('link', { name: /^Phở/ });
    expect(geo).not.toHaveBeenCalled();

    fireEvent.click(radio('Gần tôi'));
    expect(await screen.findByRole('link', { name: 'Phở, 1 quán gần bạn' })).toBeInTheDocument();
    expect(geo).toHaveBeenCalledTimes(1);
    expect(availableCalls()[1]).toEqual({ lat: USER.lat, lng: USER.lng, kinds: 'MEAL,DRINK,DESSERT' });
    expect(screen.queryByText(/71 quán/)).not.toBeInTheDocument();   // no counts from the old scope
  });

  it('AREA: the area list is the server catalog; choosing one sends only the area key', async () => {
    stubGeolocation('allow');
    serve({ [AVAILABLE]: byScope(), '/catalog': () => catalogBody() });
    renderSection();
    await screen.findByRole('link', { name: /^Phở/ });

    fireEvent.click(radio('Khu vực'));
    const list = await screen.findByRole('list', { name: 'Chọn khu vực' });
    expect(within(list).getAllByRole('button').map((b) => b.textContent))
      .toEqual(['Quận 1', 'Quận 3', 'Bình Thạnh', 'Thủ Đức (An Phú – Thảo Điền)']);
    expect(screen.getByText('Khu vực là vùng tìm kiếm của FoodMap, không phải ranh giới hành chính.')).toBeInTheDocument();

    fireEvent.click(within(list).getByRole('button', { name: 'Bình Thạnh' }));
    expect(await screen.findByRole('link', { name: 'Lẩu, 2 quán quanh Bình Thạnh' })).toBeInTheDocument();
    expect(availableCalls()[1]).toEqual({ area: 'binh-thanh', kinds: 'MEAL,DRINK,DESSERT' });
    expect(radio('Khu vực')).toBeChecked();
    expect(screen.getByText('Quanh Bình Thạnh')).toBeInTheDocument();
    expect(catalogCalls()).toHaveLength(1);
  });

  it('every scope change reloads from the server; the same scope within a minute is not re-fetched', async () => {
    stubGeolocation('allow');
    serve({ [AVAILABLE]: byScope(), '/catalog': () => catalogBody() });
    renderSection();
    await screen.findByRole('link', { name: /^Cà phê, 71 quán/ });

    fireEvent.click(radio('Gần tôi'));
    await screen.findByRole('link', { name: /^Cà phê, 12 quán/ });
    fireEvent.click(radio('Khu vực'));
    fireEvent.click(await screen.findByRole('button', { name: 'Bình Thạnh' }));
    await screen.findByRole('link', { name: /^Cà phê, 29 quán/ });
    fireEvent.click(radio('Trung tâm'));
    await screen.findByRole('link', { name: /^Cà phê, 71 quán/ });

    expect(availableCalls().map((p) => p.lat != null ? 'USER' : p.area ?? 'CITY')).toEqual(['CITY', 'USER', 'binh-thanh']);
  });

  it('an area the server refuses (400) falls back to the city centre and says so', async () => {
    stubGeolocation('allow');
    serve({
      [AVAILABLE]: byScope({ area: () => { throw httpError(400, { message: "Area 'binh-thanh' is not available right now" }); } }),
      '/catalog': () => catalogBody(),
    });
    renderSection();
    await screen.findByRole('link', { name: /^Phở/ });

    fireEvent.click(radio('Khu vực'));
    fireEvent.click(await screen.findByRole('button', { name: 'Bình Thạnh' }));

    expect(await screen.findByText('Khu vực này hiện chưa dùng được, FoodMap đang hiển thị quanh trung tâm TP.HCM.')).toBeInTheDocument();
    expect(await screen.findByRole('link', { name: 'Phở, 3 quán quanh trung tâm TP.HCM' })).toBeInTheDocument();
    expect(radio('Trung tâm')).toBeChecked();
    expect(screen.queryByText(/is not available/)).not.toBeInTheDocument();   // never the raw server text
  });

  it.each([['denied', 'deny'], ['unavailable (no geolocation API)', 'missing']])(
    'location %s: calm message, stays on the city centre, no re-prompt', async (_, mode) => {
      const geo = stubGeolocation(mode);
      serve({ [AVAILABLE]: byScope() });
      renderSection();
      await screen.findByRole('link', { name: /^Phở/ });

      fireEvent.click(radio('Gần tôi'));
      expect(await screen.findByText('Không lấy được vị trí. Bạn có thể chọn khu vực.')).toBeInTheDocument();
      expect(radio('Trung tâm')).toBeChecked();
      expect(screen.getByRole('link', { name: 'Phở, 3 quán quanh trung tâm TP.HCM' })).toBeInTheDocument();
      expect(availableCalls()).toHaveLength(1);
      expect(geo).toHaveBeenCalledTimes(mode === 'deny' ? 1 : 0);
    });
});

// ── Food list ─────────────────────────────────────────────────────────────────

describe('"Thèm gì?" — food list', () => {
  it('keeps the server order (catalog order, not counts) and shows the real counts', async () => {
    serve({ [AVAILABLE]: byScope() });
    renderSection();
    await screen.findByRole('link', { name: /^Phở/ });

    expect(chipNames()).toEqual([
      'Cà phê, 71 quán quanh trung tâm TP.HCM',
      'Trà sữa, 2 quán trong 5 km quanh trung tâm TP.HCM',
      'Bún, 3 quán quanh trung tâm TP.HCM',
      'Phở, 3 quán quanh trung tâm TP.HCM',
      'Pizza, 1 quán trong 5 km quanh trung tâm TP.HCM',
      'Sushi, 4 quán quanh trung tâm TP.HCM',
    ]);
    expect(screen.getByText('71 quán')).toBeInTheDocument();
    const traSua = screen.getByRole('link', { name: /^Trà sữa/ });
    expect(traSua).toHaveTextContent('2 quán · trong 5 km');                // widened, and said so
    expect(screen.getByText('Số quán FoodMap biết có món này (theo tên quán và thực đơn).')).toBeInTheDocument();
    expect(screen.getByText('4 món khác chưa có quán quanh trung tâm TP.HCM.')).toBeInTheDocument();
  });

  it('every chip shows the FoodArt of its own key, hidden from screen readers; the name stays text', async () => {
    serve({ [AVAILABLE]: byScope() });
    renderSection();
    await screen.findByRole('link', { name: /^Phở/ });
    const links = within(screen.getByRole('list', { name: /Các món có quán/ })).getAllByRole('link');
    expect(links).toHaveLength(6);
    for (const link of links) {
      const key = link.getAttribute('href').replace('/mon/', '');
      const art = link.querySelector('[data-food-art]');
      expect(art).toHaveAttribute('data-food-art', key);
      expect(art).toHaveAttribute('aria-hidden', 'true');
      expect(art.querySelector('svg')).toHaveAttribute('viewBox', '0 0 64 64');
      // the visible label is plain text next to the art, not inside the SVG
      expect(art.textContent).toBe('');
    }
    expect(within(links[3]).getByText('Phở')).toBeInTheDocument();
  });

  it('counts of 100 or more read "99+ quán"', async () => {
    const body = cityFoods();
    body.foods[0].count = 150;
    serve({ [AVAILABLE]: () => body });
    renderSection();
    expect(await screen.findByRole('link', { name: 'Cà phê, 99+ quán quanh trung tâm TP.HCM' })).toBeInTheDocument();
  });

  it('only foods with at least one restaurant are selectable — nothing is added locally', async () => {
    const body = cityFoods();
    body.foods.push({ key: 'kem', label: 'Kem', kind: 'DESSERT', parentCategory: 'dessert', count: 0, radiusMeters: 5000, radiusWidened: true });
    serve({ [AVAILABLE]: () => body });
    renderSection();
    await screen.findByRole('link', { name: /^Phở/ });
    expect(chipNames()).toHaveLength(6);
    expect(screen.queryByText('Kem')).not.toBeInTheDocument();
    expect(screen.queryByRole('link', { name: /Cơm|Lẩu|Chè/ })).not.toBeInTheDocument();
  });

  it('MEAL, DRINK and DESSERT share one list; the kind comes from the server', async () => {
    serve({ [AVAILABLE]: byScope(), '/catalog': () => catalogBody() });
    renderSection();
    await screen.findByRole('link', { name: /^Phở/ });
    const caPhe = screen.getByRole('link', { name: /^Cà phê/ });
    expect(within(caPhe).getByText('Đồ uống')).toBeInTheDocument();
    expect(within(screen.getByRole('link', { name: /^Phở/ })).queryByText(/Đồ uống|Tráng miệng/)).not.toBeInTheDocument();

    fireEvent.click(radio('Khu vực'));
    fireEvent.click(await screen.findByRole('button', { name: 'Bình Thạnh' }));
    const che = await screen.findByRole('link', { name: 'Chè, 1 quán quanh Bình Thạnh' });
    expect(within(che).getByText('Tráng miệng')).toBeInTheDocument();
  });

  it('renders whatever the server names — no local food list or keyword matching', async () => {
    serve({ [AVAILABLE]: () => ({ ...cityFoods(), foods: [
      { key: 'mon-moi', label: 'Món mới', kind: 'MEAL', parentCategory: 'unknown-category', tone: null, count: 2, radiusMeters: 3000 },
      { key: 'pho', label: 'Bún', kind: 'MEAL', parentCategory: 'noodle', tone: 'chili', count: 1, radiusMeters: 3000 },
    ] }) });
    renderSection();
    expect(await screen.findByRole('link', { name: 'Món mới, 2 quán quanh trung tâm TP.HCM' })).toHaveAttribute('href', '/mon/mon-moi');
    // the server's label is shown as-is, even if a keyword list would disagree
    expect(screen.getByRole('link', { name: 'Bún, 1 quán quanh trung tâm TP.HCM' })).toHaveAttribute('href', '/mon/pho');

    // a food without a drawing gets its category's art, never another food's drawing
    const monMoi = screen.getByRole('link', { name: /^Món mới/ });
    expect(monMoi.querySelector('[data-food-art]')).toBeNull();
    expect(monMoi.querySelector('[aria-hidden="true"] svg')).not.toBeNull();
    expect(screen.getByRole('link', { name: /^Bún, 1 quán/ }).querySelector('[data-food-art]'))
      .toHaveAttribute('data-food-art', 'pho');                  // art follows the key, not the label

    for (const file of ['./ThemGiSection.jsx', './FoodChip.jsx', '../location/ScopeBar.jsx', '../location/AreaPicker.jsx']) {
      const source = readFileSync(new URL(file, import.meta.url), 'utf8');
      expect(source, file).not.toMatch(/inferCategory|KEYWORDS|keywords/);
    }
  });

  it('empty availability: honest empty state, no invented food, no silent scope change', async () => {
    serve({ [AVAILABLE]: () => emptyFoods() });
    renderSection();
    expect(await screen.findByText('Chưa có món nào FoodMap biết quanh trung tâm TP.HCM.', { selector: 'p.text-body' }))
      .toBeInTheDocument();
    expect(screen.queryByRole('list', { name: /Các món có quán/ })).not.toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Chọn khu vực' })).toBeInTheDocument();
    expect(radio('Trung tâm')).toBeChecked();
    expect(availableCalls()).toHaveLength(1);
  });
});

// ── Navigation ────────────────────────────────────────────────────────────────

describe('"Thèm gì?" — navigation', () => {
  it('a chip opens /mon/{canonical key} with no scope params for the city centre', async () => {
    serve({ [AVAILABLE]: byScope() });
    renderSection();
    fireEvent.click(await screen.findByRole('link', { name: /^Trà sữa/ }));
    expect(screen.getByTestId('where')).toHaveTextContent(/^\/mon\/tra-sua$/);
  });

  it('carries the area key, or near=1 — never coordinates', async () => {
    stubGeolocation('allow');
    serve({ [AVAILABLE]: byScope(), '/catalog': () => catalogBody() });
    renderSection();
    await screen.findByRole('link', { name: /^Phở/ });

    fireEvent.click(radio('Gần tôi'));
    const near = await screen.findByRole('link', { name: /^Sushi, 4 quán trong 3 km gần bạn/ });
    expect(near).toHaveAttribute('href', '/mon/sushi?near=1');

    fireEvent.click(radio('Khu vực'));
    fireEvent.click(await screen.findByRole('button', { name: 'Bình Thạnh' }));
    const lau = await screen.findByRole('link', { name: /^Lẩu/ });
    expect(lau).toHaveAttribute('href', '/mon/lau?area=binh-thanh');

    const hrefs = screen.getAllByRole('link').map((a) => a.getAttribute('href'));
    hrefs.forEach((href) => expect(href).not.toMatch(/lat|lng|10\.77|106\.69/));
    expect(document.body.textContent).not.toMatch(/10\.77|106\.69/);   // no raw coordinates in the UI either

    fireEvent.click(lau);
    expect(screen.getByTestId('where')).toHaveTextContent('/mon/lau?area=binh-thanh');
  });
});

// ── Error states ──────────────────────────────────────────────────────────────

describe('"Thèm gì?" — errors', () => {
  it.each([
    ['500', () => httpError(500, { message: 'NullPointerException at …' })],
    ['network failure', () => networkError()],
    ['400 for the city centre', () => httpError(400, { message: 'kinds accepts MEAL, DRINK and DESSERT.' })],
  ])('%s: no counts, Vietnamese message, retry works', async (_, makeError) => {
    let fail = true;
    serve({ [AVAILABLE]: () => { if (fail) throw makeError(); return cityFoods(); } });
    renderSection();

    expect(await screen.findByText('Chưa tải được danh sách món.', { selector: 'p.text-body' })).toBeInTheDocument();
    expect(screen.getByText('FoodMap đang gặp chút trục trặc, bạn thử lại nhé.')).toBeInTheDocument();
    expect(screen.queryByText(/quán$/)).not.toBeInTheDocument();
    expect(document.body.textContent).not.toMatch(/NullPointer|Exception|kinds accepts/);

    fail = false;
    fireEvent.click(screen.getByRole('button', { name: 'Thử lại' }));
    expect(await screen.findByRole('link', { name: /^Phở, 3 quán/ })).toBeInTheDocument();
    expect(availableCalls()).toHaveLength(2);
  });

  it('429: friendly wait message, retry disabled for Retry-After seconds, never retried automatically', async () => {
    vi.useFakeTimers();
    let fail = true;
    serve({ [AVAILABLE]: () => { if (fail) throw httpError(429, { headers: { 'retry-after': '3' } }); return cityFoods(); } });
    renderSection();
    await act(() => vi.advanceTimersByTimeAsync(0));

    expect(screen.getByText('Bạn mở hơi nhanh, đợi 3 giây rồi thử lại nhé.')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Thử lại' })).toBeDisabled();
    await act(() => vi.advanceTimersByTimeAsync(1000));
    expect(screen.getByText('Bạn mở hơi nhanh, đợi 2 giây rồi thử lại nhé.')).toBeInTheDocument();
    await act(() => vi.advanceTimersByTimeAsync(2000));
    expect(screen.getByRole('button', { name: 'Thử lại' })).toBeEnabled();
    expect(screen.getByText('Giờ bạn thử lại được rồi.')).toBeInTheDocument();
    await act(() => vi.advanceTimersByTimeAsync(30_000));
    expect(availableCalls()).toHaveLength(1);                        // no automatic retries

    fail = false;
    fireEvent.click(screen.getByRole('button', { name: 'Thử lại' }));
    await act(() => vi.advanceTimersByTimeAsync(0));
    expect(screen.getByRole('link', { name: /^Phở, 3 quán/ })).toBeInTheDocument();
    expect(availableCalls()).toHaveLength(2);
  });

  it('429 without a readable Retry-After: generic wait, retry available', async () => {
    serve({ [AVAILABLE]: () => { throw httpError(429); } });
    renderSection();
    expect(await screen.findByText('Bạn mở hơi nhanh, đợi một chút rồi thử lại nhé.')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Thử lại' })).toBeEnabled();
  });
});

// ── Accessibility ─────────────────────────────────────────────────────────────

describe('"Thèm gì?" — accessibility', () => {
  it('scope is one native radio group with a legend; loading is announced and marked busy', async () => {
    let release;
    serve({ [AVAILABLE]: () => new Promise((resolve) => { release = () => resolve(cityFoods()); }) });
    renderSection();

    const group = screen.getByRole('group', { name: 'Tìm quanh đâu?' });
    const radios = within(group).getAllByRole('radio');
    expect(radios.map((r) => r.getAttribute('name'))).toEqual(Array(3).fill(radios[0].getAttribute('name')));
    expect(screen.getByRole('status')).toHaveTextContent('Đang tải các món quanh trung tâm TP.HCM…');
    expect(document.querySelector('[aria-busy="true"]')).not.toBeNull();

    await act(async () => release());
    expect(screen.getByRole('status')).toHaveTextContent('6 món có quán quanh trung tâm TP.HCM.');
    expect(document.querySelector('[aria-busy="true"]')).toBeNull();
  });

  it('keyboard: arrows move through the scope radios; the area list takes focus; Escape returns it', async () => {
    serve({ [AVAILABLE]: byScope(), '/catalog': () => catalogBody() });
    const user = userEvent.setup();
    renderSection();
    await screen.findByRole('link', { name: /^Phở/ });

    radio('Trung tâm').focus();
    await user.keyboard('{ArrowRight}');
    expect(radio('Khu vực')).toBeChecked();
    const quan1 = await screen.findByRole('button', { name: 'Quận 1' });
    expect(quan1).toHaveFocus();
    expect(quan1).toHaveAttribute('aria-pressed', 'false');

    await user.keyboard('{Escape}');
    expect(screen.queryByRole('list', { name: 'Chọn khu vực' })).not.toBeInTheDocument();
    expect(radio('Trung tâm')).toBeChecked();
    expect(radio('Trung tâm')).toHaveFocus();

    // choose an area with the keyboard; focus lands on "Đổi khu vực"
    await user.keyboard('{ArrowRight}');
    await user.tab();                                   // into the list (focus is already there) → next area
    await user.keyboard('{Enter}');
    expect(await screen.findByRole('button', { name: 'Đổi khu vực' })).toHaveFocus();
    expect(availableCalls().at(-1)).toEqual(expect.objectContaining({ area: expect.any(String) }));
  });

  it('touch targets are at least 44 px; information never depends on animation', async () => {
    serve({ [AVAILABLE]: byScope() });
    renderSection();
    await screen.findByRole('link', { name: /^Phở/ });
    for (const link of within(screen.getByRole('list', { name: /Các món có quán/ })).getAllByRole('link')) {
      expect(link.className).toMatch(/\bmin-h-11\b/);
      expect(link.className).not.toMatch(/animate-/);
    }
    for (const r of screen.getAllByRole('radio')) {
      expect(r.nextElementSibling.className).toMatch(/\bmin-h-11\b/);
    }
    // labels and counts are plain text, present without waiting for any transition
    expect(screen.getAllByText('3 quán', { selector: 'span' })).toHaveLength(2);
  });
});

// ── Copy honesty ──────────────────────────────────────────────────────────────

describe('"Thèm gì?" — copy', () => {
  it('no popularity, quality or game words anywhere in the section', () => {
    const files = ['./ThemGiSection.jsx', './FoodChip.jsx', '../location/ScopeBar.jsx', '../location/AreaPicker.jsx', '../../pages/FoodPage.jsx'];
    const forbidden = /Phổ biến|Đang hot|\bhot\b|Đánh giá cao|Giá thấp|xu hướng|trending|ngon nhất|yêu thích|may mắn|lucky|trúng|jackpot|\bXP\b|rarity|\bwin\b/i;
    for (const file of files) {
      expect(readFileSync(new URL(file, import.meta.url), 'utf8'), file).not.toMatch(forbidden);
    }
  });
});
