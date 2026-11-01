import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { act, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter, Route, Routes, useLocation, useNavigate } from 'react-router-dom';
import goongjs from '@goongmaps/goong-js';
import apiClient from '../api/client';
import MapPage from './MapPage';
import { resetCatalogCache } from '../hooks/useCatalog';
import { resetLocationScope } from '../hooks/useLocationScope';
import { resetMapMemory } from '../components/map/mapMemory';
import { MAP_RESTAURANTS, mapCatalogBody } from '../test/mapFixtures';

vi.mock('../api/client', () => ({ default: { get: vi.fn() } }));
vi.mock('../hooks/useAuth', () => ({ useAuth: () => ({ isAuthenticated: false, user: null, logout: vi.fn() }) }));
vi.mock('@goongmaps/goong-js', () => import('../test/fakeGoong.js'));

// ── Harness ───────────────────────────────────────────────────────────────────

let handlers;
let navigateTo;
const PANEL_HEIGHT = 240;
const observers = [];
/** Report a new size for `el` to every ResizeObserver watching it. */
const reportSize = (el, width, height) => act(() => {
  observers.filter((o) => o.el === el).forEach((o) => o.cb([{ target: el, contentRect: { width, height } }]));
});

/** A device of `width` CSS px: media queries answer like a browser would. */
function useWidth(width, { reduce = false } = {}) {
  vi.stubGlobal('innerWidth', width);
  vi.stubGlobal('matchMedia', (query) => {
    const max = query.match(/max-width:\s*(\d+)px/);
    const min = query.match(/min-width:\s*(\d+)px/);
    const matches = query.includes('reduced-motion') ? reduce
      : max ? width <= Number(max[1])
      : min ? width >= Number(min[1])
      : false;
    return { matches, media: query, addEventListener() {}, removeEventListener() {} };
  });
}

const apiCalls = () => apiClient.get.mock.calls.map(([url]) => url);
const callsTo = (url) => apiCalls().filter((u) => u === url);

function Where() {
  const { pathname, search, state } = useLocation();
  return (
    <>
      <p data-testid="where">{pathname + search}</p>
      <p data-testid="state">{state ? JSON.stringify(state) : ''}</p>
    </>
  );
}
function Nav() {
  navigateTo = useNavigate();
  return null;
}

const renderAt = (url) => render(
  <MemoryRouter initialEntries={[url]}>
    <Nav />
    <Routes>
      <Route path="/map" element={<><MapPage /><Where /></>} />
      <Route path="/restaurants/:id" element={<Where />} />
      <Route path="/mon/:foodKey" element={<Where />} />
    </Routes>
  </MemoryRouter>,
);

const map = () => goongjs.__state.maps.at(-1);
const pin = (id) => document.querySelector(`[data-pin-id="${id}"]`);
const where = () => screen.getByTestId('where').textContent;
const layout = () => document.querySelector('main').dataset.mapLayout;
const panel = () => document.querySelector('[data-map-panel]');
const mapArea = () => document.querySelector('.map-container').parentElement.parentElement;
const chip = (label) => screen.getByRole('button', { name: new RegExp(`^${label},`) });
const toggle = () => document.querySelector('[data-view-toggle]');

async function loadMap() {
  await act(async () => map().fire('load'));
  await waitFor(() => expect(callsTo('/restaurants/viewport')).toHaveLength(1), { timeout: 2000 });
  await waitFor(() => expect(pin(1)).not.toBeNull());
}

beforeEach(() => {
  handlers = {
    '/catalog': () => mapCatalogBody(),
    '/restaurants/viewport': () => MAP_RESTAURANTS,
  };
  apiClient.get.mockReset();
  apiClient.get.mockImplementation(async (url, config) => {
    const handler = handlers[url];
    if (!handler) throw new Error(`unexpected request ${url}`);
    return { data: await handler(config?.params ?? {}), headers: {} };
  });
  goongjs.__state.maps.length = 0;
  Element.prototype.scrollIntoView = vi.fn();
  // the panel measures itself (jsdom has no layout); tests can report sizes
  observers.length = 0;
  vi.stubGlobal('ResizeObserver', class {
    constructor(cb) { this.cb = cb; observers.push(this); }
    observe(el) { this.el = el; this.cb([{ target: el, contentRect: { width: 0, height: 0 } }]); }
    disconnect() { this.el = null; }
  });
  vi.spyOn(Element.prototype, 'getBoundingClientRect').mockImplementation(function rect() {
    const height = this.hasAttribute?.('data-map-panel') ? PANEL_HEIGHT : 0;
    return { x: 0, y: 0, top: 0, left: 0, right: 0, bottom: height, width: 0, height };
  });
  resetCatalogCache();
  resetLocationScope();
  resetMapMemory();
});

afterEach(() => {
  vi.unstubAllGlobals();
});

// ── Layout per width ──────────────────────────────────────────────────────────

describe('phone layout', () => {
  it.each([320, 375, 390, 430])('%i px: the map fills the page; the controls are a bottom panel, no 256 px column', async (width) => {
    useWidth(width);
    renderAt('/map');
    await loadMap();

    expect(layout()).toBe('mobile-map');
    expect(panel().className).toMatch(/\babsolute\b/);
    expect(panel().className).toMatch(/\binset-x-0\b/);
    expect(panel().className).toMatch(/\bbottom-0\b/);
    expect(panel().className).not.toMatch(/\bw-64\b|\bsm:w-80\b/);
    expect(panel()).toHaveAccessibleName('Bảng điều khiển bản đồ');
    // the panel holds mode + count + the list switch; the list itself waits for "Danh sách"
    expect(within(panel()).getByRole('radio', { name: 'Quán' })).toBeChecked();
    expect(within(panel()).getByText('6 quán trong khung bản đồ')).toBeInTheDocument();
    expect(toggle()).toHaveTextContent('Danh sách');
    expect(document.querySelector('[data-map-list]')).toBeNull();
    expect(mapArea().hasAttribute('inert')).toBe(false);
  });

  it('320 px: food mode fits — rail, header and switch all in the panel', async () => {
    useWidth(320);
    renderAt('/map?food=pho');
    await loadMap();
    expect(within(panel()).getByRole('list', { name: 'Chọn món' })).toBeInTheDocument();
    expect(within(panel()).getByText('3 quán có Phở trong khung bản đồ')).toBeInTheDocument();
    expect(within(panel()).getByRole('link', { name: 'Xem trang món Phở' })).toHaveAttribute('href', '/mon/pho');
    expect(toggle()).toBeInTheDocument();
    // the selected food's pin treatment is the P1 one
    expect([1, 2, 6].map((id) => pin(id).dataset.pinState)).toEqual(['match', 'match', 'match']);
    expect(pin(5).dataset.pinState).toBe('dim');
  });

  it('the panel height lifts Goong\'s bottom controls and centres a selection above it', async () => {
    useWidth(375);
    renderAt('/map');
    await loadMap();
    expect(document.querySelector('main').style.getPropertyValue('--fm-map-panel-h')).toBe(`${PANEL_HEIGHT}px`);
    await act(async () => pin(3).click());
    expect(map().camera.filter(([n]) => n === 'easeTo').map(([, o]) => o.offset)).toEqual([[0, -PANEL_HEIGHT / 2]]);
    const css = readFileSync(resolve(process.cwd(), 'src/index.css'), 'utf8');
    expect(css).toMatch(/\[data-map-layout='mobile-map'\] \.mapboxgl-ctrl-bottom-left[\s\S]*bottom:\s*var\(--fm-map-panel-h/);
  });

  it('768 px and up keep the desktop sidebar and Goong popups, unchanged', async () => {
    for (const width of [768, 1024, 1280]) {
      useWidth(width);
      const view = renderAt('/map');
      await loadMap();
      expect(layout()).toBe('desktop');
      expect(panel().className).toBe('flex w-64 flex-shrink-0 flex-col border-r border-line bg-cream sm:w-80 lg:w-96 overflow-hidden');
      expect(toggle()).toBeNull();
      expect(document.querySelector('[data-map-list]')).not.toBeNull();
      await act(async () => pin(1).click());
      await waitFor(() => expect(document.querySelector('[data-popup]')).not.toBeNull());
      expect(document.querySelector('[data-map-preview]')).toBeNull();
      expect(map().camera.filter(([n]) => n === 'easeTo').map(([, o]) => o.offset)).toEqual([[0, 80]]);
      view.unmount();
      goongjs.__state.maps.length = 0;
      apiClient.get.mockClear();
      resetMapMemory();
    }
  });
});

// ── Map ⇄ List ────────────────────────────────────────────────────────────────

describe('Map / List switch', () => {
  it('shows the same loaded restaurants as a list — no request, no new map, markers untouched', async () => {
    useWidth(375);
    renderAt('/map');
    await loadMap();
    const pins = [1, 2, 3, 4, 5, 6].map(pin);
    const requests = apiCalls().length;
    const maps = goongjs.__state.maps.length;
    const camera = map().camera.length;

    fireEvent.click(toggle());

    expect(layout()).toBe('mobile-list');
    expect(panel().className).toMatch(/\binset-0\b/);
    expect(panel()).toHaveAccessibleName('Danh sách quán');
    const list = document.querySelector('[data-map-list]');
    expect(within(list).getAllByRole('link').map((a) => a.getAttribute('href')).sort())
      .toEqual(['/restaurants/1', '/restaurants/2', '/restaurants/3', '/restaurants/4', '/restaurants/5', '/restaurants/6']);
    // the sort tabs get phone-size targets (desktop keeps its compact tabs)
    within(within(panel()).getByRole('group', { name: 'Sắp xếp' })).getAllByRole('button')
      .forEach((b) => expect(b.className).toMatch(/\bmin-h-11\b/));
    expect(mapArea()).toHaveAttribute('inert');
    expect(mapArea()).toHaveAttribute('aria-hidden', 'true');
    expect(toggle()).toHaveTextContent('Bản đồ');

    fireEvent.click(toggle());
    expect(layout()).toBe('mobile-map');
    expect(mapArea().hasAttribute('inert')).toBe(false);

    await new Promise((r) => setTimeout(r, 800));                  // past the viewport debounce
    expect(apiCalls()).toHaveLength(requests);
    expect(goongjs.__state.maps).toHaveLength(maps);
    expect(map().camera).toHaveLength(camera);
    expect([1, 2, 3, 4, 5, 6].map(pin)).toEqual(pins);
  });

  it('keeps the chosen food across switches: list sections, pressed chip, pins, URL', async () => {
    useWidth(390);
    renderAt('/map?food=pho');
    await loadMap();

    fireEvent.click(toggle());
    expect(where()).toBe('/map?food=pho');
    expect(chip('Phở')).toHaveAttribute('aria-pressed', 'true');
    expect(screen.getByRole('heading', { level: 3, name: 'Có Phở (3)' })).toBeInTheDocument();
    expect(screen.getByText('Quán khác trong khung (3)')).toBeInTheDocument();
    expect(document.querySelector('#card-6 [data-food-pill]')).toHaveTextContent('Phở');

    // choosing another food in the list view: still no request
    const requests = apiCalls().length;
    fireEvent.click(chip('Bún'));
    expect(where()).toBe('/map?food=bun');
    expect(screen.getByRole('heading', { level: 3, name: 'Có Bún (2)' })).toBeInTheDocument();

    fireEvent.click(toggle());
    expect([2, 6].map((id) => pin(id).dataset.pinState)).toEqual(['match', 'match']);
    expect(pin(1).dataset.pinState).toBe('dim');
    expect(apiCalls()).toHaveLength(requests);
    expect(callsTo('/catalog')).toHaveLength(1);
  });

  it('the list keeps its scroll position', async () => {
    useWidth(375);
    renderAt('/map');
    await loadMap();
    fireEvent.click(toggle());
    document.querySelector('[data-map-list]').scrollTop = 180;
    fireEvent.click(toggle());
    fireEvent.click(toggle());
    expect(document.querySelector('[data-map-list]').scrollTop).toBe(180);
  });

  it('keeps the list usable: loading skeletons and the one error message', async () => {
    useWidth(375);
    let release;
    handlers['/restaurants/viewport'] = () => new Promise((ok) => { release = () => ok(MAP_RESTAURANTS); });
    renderAt('/map');
    await act(async () => map().fire('load'));
    fireEvent.click(toggle());
    await waitFor(() => expect(document.querySelectorAll('[data-map-list] .animate-pulse').length).toBeGreaterThan(0), { timeout: 2000 });
    await act(async () => release());
    await waitFor(() => expect(pin(1)).not.toBeNull());

    // errors show once, in whichever view is open
    handlers['/restaurants/viewport'] = () => { throw Object.assign(new Error('Network Error'), { code: 'ERR_NETWORK' }); };
    map().center = { lng: 106.71, lat: 10.78 };
    await act(async () => map().fire('moveend'));
    expect(await screen.findByText('Không tải được danh sách quán', {}, { timeout: 2000 })).toBeInTheDocument();
    fireEvent.click(toggle());
    expect(screen.getAllByText('Không tải được danh sách quán')).toHaveLength(1);
    expect(within(panel()).getByRole('button', { name: 'Thử lại' })).toBeInTheDocument();
  });
});

// ── Bottom card ───────────────────────────────────────────────────────────────

describe('the selected restaurant\'s bottom card', () => {
  it('opens on a pin tap instead of a Goong popup, with only the facts the data has', async () => {
    useWidth(375);
    renderAt('/map?food=pho');
    await loadMap();
    fireEvent.mouseEnter(pin(1));
    expect(document.querySelector('[data-popup]')).toBeNull();          // no hover popup on the phone

    await act(async () => pin(2).click());

    expect(document.querySelector('[data-popup]')).toBeNull();
    const card = screen.getByRole('region', { name: 'Quán đang chọn' });
    expect(within(card).getByText('Bún Phở Cô Ba')).toBeInTheDocument();
    expect(card.querySelector('[data-food-note]')).toHaveTextContent('Có món Phở');
    expect([...card.querySelectorAll('[data-food-pill]')].map((p) => p.textContent)).toEqual(['Phở', 'Bún']);
    // no invented facts: no stars, prices, distance, opening hours or photos
    expect(card.textContent).not.toMatch(/★|₫|\d\s?(k?m)\b|Đang mở|Đóng cửa|phổ biến/i);
    expect(card.querySelector('img')).toBeNull();
    // the count header makes room for it; the chosen food stays visible in the rail
    expect(document.querySelector('[data-food-header]')).toBeNull();
    expect(chip('Phở')).toHaveAttribute('aria-pressed', 'true');

    const close = within(card).getByRole('button', { name: 'Đóng' });
    expect(close.className).toMatch(/\bh-11\b/);
    expect(close.className).toMatch(/\bw-11\b/);
    fireEvent.click(close);
    expect(screen.queryByRole('region', { name: 'Quán đang chọn' })).toBeNull();
    expect(document.querySelector('[data-food-header]')).not.toBeNull();
  });

  it('"Xem chi tiết" is a real link carrying { fromFood } for a match only', async () => {
    useWidth(430);
    renderAt('/map?food=pho');
    await loadMap();
    await act(async () => pin(3).click());                             // a dot: still tappable
    const plain = within(screen.getByRole('region', { name: 'Quán đang chọn' })).getByRole('link', { name: 'Xem chi tiết' });
    expect(plain).toHaveAttribute('href', '/restaurants/3');
    expect(plain.className).toMatch(/\bh-11\b/);

    await act(async () => pin(2).click());
    fireEvent.click(within(screen.getByRole('region', { name: 'Quán đang chọn' })).getByRole('link', { name: 'Xem chi tiết' }));
    expect(where()).toBe('/restaurants/2');
    expect(screen.getByTestId('state')).toHaveTextContent('{"fromFood":"pho"}');
  });

  it('Escape closes it', async () => {
    useWidth(375);
    renderAt('/map');
    await loadMap();
    await act(async () => pin(1).click());
    expect(screen.getByRole('region', { name: 'Quán đang chọn' })).toBeInTheDocument();
    fireEvent.keyDown(document, { key: 'Escape' });
    expect(screen.queryByRole('region', { name: 'Quán đang chọn' })).toBeNull();
    expect(pin(1).classList.contains('restaurant-pin--active')).toBe(false);
  });
});

// ── Food rail on a narrow screen ──────────────────────────────────────────────

describe('food rail on a phone', () => {
  it('scrolls sideways inside itself, chips keep 44 px and catalog order', async () => {
    useWidth(320);
    renderAt('/map?food=pho');
    await loadMap();
    const rail = screen.getByRole('list', { name: 'Chọn món' });
    expect(rail.className).toMatch(/\boverflow-x-auto\b/);
    expect(rail.className).toMatch(/\boverscroll-x-contain\b/);
    const chips = within(rail).getAllByRole('button');
    expect(chips.map((c) => c.dataset.foodChip)).toEqual(['ca-phe', 'bun', 'pho', 'com', 'banh-mi', 'kem']);
    chips.forEach((c) => {
      expect(c.className).toMatch(/\bmin-h-11\b/);
      expect(c.className).toMatch(/w-\[4\.25rem\]/);               // compact, not stretched
      expect(c.parentElement.className).toMatch(/\bflex-shrink-0\b/);
    });
  });

  it('brings a chosen food at the end of the rail into view, sideways only', async () => {
    useWidth(320);
    // jsdom has no layout: chips 68 px wide on a 72 px pitch, rail 280 px wide
    const index = (el) => [...el.parentElement.children].indexOf(el);
    vi.spyOn(HTMLElement.prototype, 'offsetLeft', 'get').mockImplementation(function left() {
      return this.tagName === 'LI' && this.closest('[aria-label="Chọn món"]') ? 12 + index(this) * 72 : 0;
    });
    vi.spyOn(HTMLElement.prototype, 'offsetWidth', 'get').mockImplementation(function width() { return this.tagName === 'LI' ? 68 : 280; });
    vi.spyOn(HTMLElement.prototype, 'clientWidth', 'get').mockImplementation(() => 280);

    renderAt('/map?food=kem');
    await loadMap();
    const rail = screen.getByRole('list', { name: 'Chọn món' });
    expect(chip('Kem')).toHaveAttribute('aria-pressed', 'true');
    expect(rail.scrollLeft).toBeGreaterThan(0);
    expect(Element.prototype.scrollIntoView).not.toHaveBeenCalled();   // the page itself never scrolls
  });
});

// ── Rotation across the breakpoint ────────────────────────────────────────────

describe('rotation (the layout changes the map\'s size)', () => {
  it('the map resizes when its container does — and only then', async () => {
    useWidth(375);
    renderAt('/map');
    await loadMap();
    const container = document.querySelector('.map-container');
    const resizes = () => map().camera.filter(([n]) => n === 'resize').length;

    reportSize(container, 375, 671);
    const settled = resizes();
    reportSize(container, 375, 671);                                // same size: nothing
    expect(resizes()).toBe(settled);
    fireEvent.click(toggle());                                     // the list covers the map; no size change
    fireEvent.click(toggle());
    expect(resizes()).toBe(settled);

    reportSize(container, 448, 959);                                // phone → desktop sidebar layout
    expect(resizes()).toBe(settled + 1);
  });

  it('the chosen chip comes back into view when the rail gets narrower', async () => {
    useWidth(430);
    let railWidth = 600;
    const index = (el) => [...el.parentElement.children].indexOf(el);
    vi.spyOn(HTMLElement.prototype, 'offsetLeft', 'get').mockImplementation(function left() {
      return this.tagName === 'LI' && this.closest('[aria-label="Chọn món"]') ? 12 + index(this) * 72 : 0;
    });
    vi.spyOn(HTMLElement.prototype, 'offsetWidth', 'get').mockImplementation(function width() { return this.tagName === 'LI' ? 68 : railWidth; });
    vi.spyOn(HTMLElement.prototype, 'clientWidth', 'get').mockImplementation(() => railWidth);

    renderAt('/map?food=kem');
    await loadMap();
    const rail = screen.getByRole('list', { name: 'Chọn món' });
    expect(rail.scrollLeft).toBe(0);                                // fits on the wide rail

    railWidth = 280;
    reportSize(rail, 280, 90);
    expect(rail.scrollLeft).toBeGreaterThan(0);
  });
});

// ── Back, safe areas, motion, keyboard ────────────────────────────────────────

describe('Back from a restaurant on a phone', () => {
  it('returns to the list view with the food still chosen, without re-asking the catalog', async () => {
    useWidth(375);
    renderAt('/map?food=pho');
    await loadMap();
    fireEvent.click(toggle());
    fireEvent.click(document.querySelector('#card-2 a'));
    expect(where()).toBe('/restaurants/2');
    expect(screen.getByTestId('state')).toHaveTextContent('{"fromFood":"pho"}');

    act(() => navigateTo(-1));
    expect(where()).toBe('/map?food=pho');
    expect(layout()).toBe('mobile-list');
    expect(chip('Phở')).toHaveAttribute('aria-pressed', 'true');
    expect(screen.getByRole('heading', { level: 3, name: 'Có Phở (3)' })).toBeInTheDocument();
    expect(callsTo('/catalog')).toHaveLength(1);
  });

  it('returns to the map with the bottom card still open and no pan', async () => {
    useWidth(375);
    renderAt('/map?food=pho');
    await loadMap();
    await act(async () => pin(2).click());
    fireEvent.click(within(screen.getByRole('region', { name: 'Quán đang chọn' })).getByRole('link', { name: 'Xem chi tiết' }));
    act(() => navigateTo(-1));

    expect(layout()).toBe('mobile-map');
    expect(within(screen.getByRole('region', { name: 'Quán đang chọn' })).getByText('Bún Phở Cô Ba')).toBeInTheDocument();
    expect(pin(2).classList.contains('restaurant-pin--active')).toBe(true);
    expect(map().camera.filter(([n]) => n === 'easeTo' || n === 'flyTo')).toEqual([]);
  });
});

describe('safe areas', () => {
  it('the page pads for the notch and the home indicator, and uses the dynamic viewport height', async () => {
    useWidth(375);
    renderAt('/map');
    const root = document.querySelector('main').parentElement;
    expect(root.className).toMatch(/pt-\[env\(safe-area-inset-top\)\]/);
    expect(root.className).toMatch(/\bpb-bottom-nav\b/);
    expect(root.className).toMatch(/supports-\[height:100dvh\]:h-dvh/);
    const css = readFileSync(resolve(process.cwd(), 'src/index.css'), 'utf8');
    expect(css).toMatch(/\.pb-bottom-nav\s*\{\s*padding-bottom:\s*calc\(var\(--fm-bottom-nav-h\) \+ env\(safe-area-inset-bottom\)\)/);
    await loadMap();
    expect(panel().className).toMatch(/\bpb-6\b/);                  // clears the nav's raised button
    fireEvent.click(toggle());
    expect(toggle().parentElement.className).toMatch(/\bbottom-8\b/);
    expect(document.querySelector('[data-map-list]').className).toMatch(/\bpb-24\b/);
  });
});

describe('reduced motion on a phone', () => {
  it('a selection pans instantly', async () => {
    useWidth(375, { reduce: true });
    renderAt('/map');
    await loadMap();
    await act(async () => pin(1).click());
    expect(map().camera.filter(([n]) => n === 'easeTo').map(([, o]) => o.duration)).toEqual([0]);
  });
});

describe('keyboard on a phone layout', () => {
  it('the switch works from the keyboard and focus follows it; the map behind the list is inert', async () => {
    const user = userEvent.setup();
    useWidth(375);
    renderAt('/map');
    await loadMap();

    toggle().focus();
    await user.keyboard('{Enter}');
    expect(layout()).toBe('mobile-list');
    expect(toggle()).toHaveTextContent('Bản đồ');
    expect(toggle()).toHaveFocus();
    expect(toggle().className).toMatch(/\bmin-h-11\b/);
    expect(toggle().className).toMatch(/focus-visible:outline/);
    expect(mapArea()).toHaveAttribute('inert');

    await user.keyboard(' ');
    expect(layout()).toBe('mobile-map');
    expect(toggle()).toHaveTextContent('Danh sách');
    expect(toggle()).toHaveFocus();

    // the mode radios and the rail behave as on desktop
    screen.getByRole('radio', { name: 'Quán' }).focus();
    await user.keyboard('{ArrowRight}');
    expect(screen.getByRole('radio', { name: 'Món' })).toBeChecked();
    chip('Phở').focus();
    await user.keyboard('{Enter}');
    expect(where()).toBe('/map?food=pho');
    expect(chip('Phở')).toHaveFocus();
  });
});
