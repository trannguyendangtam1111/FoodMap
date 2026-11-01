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
import { httpError, networkError } from '../test/foodFixtures';

vi.mock('../api/client', () => ({ default: { get: vi.fn() } }));
vi.mock('../hooks/useAuth', () => ({ useAuth: () => ({ isAuthenticated: false, user: null, logout: vi.fn() }) }));

// A stand-in for Goong GL: markers and popups are plain DOM inside the map
// container, so pins can be inspected; camera calls are recorded.
vi.mock('@goongmaps/goong-js', () => {
  const state = { maps: [] };
  class FakeMap {
    constructor(options) {
      this.options = options;
      this.handlers = {};
      this.camera = [];
      this.center = { lng: options.center[0], lat: options.center[1] };
      this.zoom = options.zoom;
      this.layer = document.createElement('div');
      this.layer.dataset.mapLayer = '';
      options.container.appendChild(this.layer);
      state.maps.push(this);
    }
    addControl() { return this; }
    on(event, fn) { (this.handlers[event] ??= []).push(fn); return this; }
    fire(event) { (this.handlers[event] ?? []).forEach((fn) => fn()); }
    getBounds() {
      const { lng, lat } = this.center;
      return {
        getSouth: () => lat - 0.01, getWest: () => lng - 0.02, getNorth: () => lat + 0.01, getEast: () => lng + 0.02,
      };
    }
    getCenter() { return this.center; }
    getZoom() { return this.zoom; }
    flyTo(o) { this.camera.push(['flyTo', o]); }
    easeTo(o) { this.camera.push(['easeTo', o]); }
    fitBounds(b, o) { this.camera.push(['fitBounds', o]); }
    zoomOut(o) { this.camera.push(['zoomOut', o]); }
    remove() { this.layer.remove(); }
  }
  class FakeMarker {
    constructor({ element }) { this.el = element; }
    setLngLat(ll) { this.ll = ll; return this; }
    getLngLat() { return { lng: this.ll[0], lat: this.ll[1] }; }
    addTo(map) { map.layer.appendChild(this.el); return this; }
    remove() { this.el.remove(); }
  }
  class FakePopup {
    constructor(options) { this.options = options; this.handlers = {}; }
    setDOMContent(node) { this.node = node; node.dataset.popup = ''; return this; }
    setLngLat() { return this; }
    addTo(map) { map.layer.appendChild(this.node); return this; }
    on(event, fn) { this.handlers[event] = fn; return this; }
    remove() { this.node.remove(); this.handlers.close?.(); }
  }
  class LngLatBounds { extend() { return this; } }
  class Control {}
  return {
    default: {
      Map: FakeMap, Marker: FakeMarker, Popup: FakePopup, LngLatBounds,
      NavigationControl: Control, ScaleControl: Control, GeolocateControl: Control,
      __state: state,
    },
  };
});

// ── Fixtures (server-shaped, already unwrapped) ───────────────────────────────

const catalogBody = () => ({
  version: 'v1',
  foods: [
    { key: 'ca-phe', label: 'Cà phê', kind: 'DRINK', parentCategory: 'coffee', tone: 'taro', discoverable: true, artKey: 'ca-phe' },
    { key: 'bun', label: 'Bún', kind: 'MEAL', parentCategory: 'noodle', tone: 'chili', discoverable: true, artKey: 'bun' },
    { key: 'pho', label: 'Phở', kind: 'MEAL', parentCategory: 'noodle', tone: 'chili', discoverable: true, artKey: 'pho' },
    { key: 'com', label: 'Cơm', kind: 'MEAL', parentCategory: 'rice', tone: 'turmeric', discoverable: true, artKey: 'com' },
    { key: 'banh-mi', label: 'Bánh mì', kind: 'MEAL', parentCategory: 'bakery', tone: 'crust', discoverable: true, artKey: 'banh-mi' },
    { key: 'tra', label: 'Trà', kind: 'DRINK', parentCategory: 'coffee', tone: 'taro', discoverable: false, artKey: 'tra' },
  ],
  areas: [],
});

const place = (id, name, foodKeys, i) => ({
  id, name, address: `${id} Đường Số ${id}, Quận 1`,
  latitude: 10.776 + i * 0.001, longitude: 106.700 + i * 0.001,
  avgRating: 0, reviewCount: 0, ...(foodKeys ? { foodKeys } : {}),
});

const RESTAURANTS = [
  place(1, 'Phở Hòa', ['pho'], 0),
  place(2, 'Bún Phở Cô Ba', ['bun', 'pho'], 1),
  place(3, 'Bánh Mì Hai Lúa', ['banh-mi'], 2),
  place(4, 'Cafe Góc Phố', ['ca-phe', 'tra'], 3),
  // a name that says phở, but the server didn't classify it: never a match
  place(5, 'Phở Không Phân Loại', null, 4),
  place(6, 'Quán Đủ Món', ['bun', 'pho', 'banh-mi', 'ca-phe'], 5),
];

let handlers;
let navigateTo;

const apiCalls = () => apiClient.get.mock.calls.map(([url, config]) => ({ url, params: config?.params ?? {} }));
const callsTo = (url) => apiCalls().filter((c) => c.url === url);

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
const pinState = (id) => pin(id)?.dataset.pinState;
const where = () => screen.getByTestId('where').textContent;
const chip = (label) => screen.getByRole('button', { name: new RegExp(`^${label},`) });
const modeRadio = (label) => screen.getByRole('radio', { name: label });

/** The map has loaded and the first viewport answer is on screen. */
async function loadMap() {
  await act(async () => map().fire('load'));
  await waitFor(() => expect(callsTo('/restaurants/viewport')).toHaveLength(1), { timeout: 2000 });
  await waitFor(() => expect(pin(1)).not.toBeNull());
}

beforeEach(() => {
  handlers = {
    '/catalog': () => catalogBody(),
    '/restaurants/viewport': () => RESTAURANTS,
  };
  apiClient.get.mockReset();
  apiClient.get.mockImplementation(async (url, config) => {
    const handler = handlers[url];
    if (!handler) throw new Error(`unexpected request ${url}`);
    return { data: await handler(config?.params ?? {}), headers: {} };
  });
  goongjs.__state.maps.length = 0;
  Element.prototype.scrollIntoView = vi.fn();   // not in jsdom
  resetCatalogCache();
  resetLocationScope();
  resetMapMemory();
});

afterEach(() => {
  vi.unstubAllGlobals();
});

// ── Modes and URL ─────────────────────────────────────────────────────────────

describe('/map modes', () => {
  it('opens in "Quán": the original map, pins showing each restaurant\'s primary food', async () => {
    renderAt('/map');
    expect(modeRadio('Quán')).toBeChecked();
    expect(modeRadio('Món')).not.toBeChecked();
    expect(screen.queryByRole('list', { name: 'Chọn món' })).toBeNull();
    await loadMap();

    expect(screen.getByText('6 quán trong khung bản đồ')).toBeInTheDocument();
    expect(pinState(1)).toBe('food');
    expect(pin(1).querySelector('use').getAttribute('href')).toBe('#fm-glyph-pho');
    expect(pin(2).querySelector('use').getAttribute('href')).toBe('#fm-glyph-bun');   // foodKeys[0]
    expect(pinState(5)).toBe('plain');                                                  // no server food
    expect(pin(5).classList.contains('restaurant-pin--food')).toBe(false);
    // the existing sort, rating tab included
    expect(within(screen.getByRole('group', { name: 'Sắp xếp' })).getAllByRole('button').map((b) => b.textContent))
      .toEqual(['Phù hợp', 'Gần nhất', 'Điểm cao']);
    expect(apiCalls().map((c) => c.url).sort()).toEqual(['/catalog', '/restaurants/viewport']);
  });

  it('"Món" shows the catalog\'s discoverable foods in catalog order, without a request or a URL change', async () => {
    renderAt('/map');
    await loadMap();
    const before = apiCalls().length;
    const camera = map().camera.length;

    fireEvent.click(modeRadio('Món'));

    const rail = await screen.findByRole('list', { name: 'Chọn món' });
    expect(within(rail).getAllByRole('button').map((b) => b.dataset.foodChip)).toEqual(['ca-phe', 'bun', 'pho', 'com', 'banh-mi']);
    expect(within(rail).queryByRole('button', { name: /^Trà,/ })).toBeNull();   // classification-only
    expect(within(rail).getAllByRole('button').every((b) => b.getAttribute('aria-pressed') === 'false')).toBe(true);
    expect(where()).toBe('/map');
    expect(apiCalls()).toHaveLength(before);
    expect(map().camera).toHaveLength(camera);
    expect(screen.getByText('Chọn một món để làm nổi bật quán có món đó.')).toBeInTheDocument();
  });

  it('/map?food=pho opens "Món" with phở chosen and emphasised', async () => {
    renderAt('/map?food=pho');
    expect(modeRadio('Món')).toBeChecked();
    await loadMap();

    expect(chip('Phở')).toHaveAttribute('aria-pressed', 'true');
    expect(screen.getByRole('heading', { level: 2, name: 'Phở' })).toBeInTheDocument();
    expect(document.querySelector('[data-food-count]')).toHaveTextContent('3 quán có Phở trong khung bản đồ');
    expect([1, 2, 6].map(pinState)).toEqual(['match', 'match', 'match']);
    expect([3, 4, 5].map(pinState)).toEqual(['dim', 'dim', 'dim']);
    expect(pin(2).querySelector('use').getAttribute('href')).toBe('#fm-glyph-pho');   // the chosen food's glyph
  });

  it('an unknown or classification-only food key is dropped quietly; "Món" stays', async () => {
    renderAt('/map?food=khong-co-mon-nay');
    await loadMap();
    await waitFor(() => expect(where()).toBe('/map'));
    expect(modeRadio('Món')).toBeChecked();
    expect(screen.queryAllByRole('button', { pressed: true }).filter((b) => b.dataset.foodChip)).toHaveLength(0);
    expect(screen.queryByRole('alert')).toBeNull();
    expect([1, 5].map(pinState)).toEqual(['food', 'plain']);
  });

  it('a classification-only key ("tra") is not offered either', async () => {
    renderAt('/map?food=tra');
    await loadMap();
    await waitFor(() => expect(where()).toBe('/map'));
    expect(pinState(4)).toBe('food');
  });

  it('q wins over food: the food is dropped and the search runs', async () => {
    handlers['/restaurants/search'] = () => ({ content: [RESTAURANTS[0]] });
    handlers['/foods/search'] = () => ({ content: [] });
    renderAt('/map?q=pho&food=pho');
    await waitFor(() => expect(where()).toBe('/map?q=pho'));
    expect(modeRadio('Quán')).toBeChecked();
    await waitFor(() => expect(callsTo('/restaurants/search').some((c) => c.params.query === 'pho' && c.params.size === 50)).toBe(true));
    expect(screen.queryByRole('list', { name: 'Chọn món' })).toBeNull();
  });
});

// ── Choosing, changing and clearing a food ────────────────────────────────────

describe('choosing a food', () => {
  it('re-reads the loaded restaurants: URL ?food=, pins re-classed in place, no request, the map stays', async () => {
    renderAt('/map');
    await loadMap();
    fireEvent.click(modeRadio('Món'));
    const pinsBefore = [1, 2, 3, 4, 5, 6].map(pin);
    const requests = apiCalls().length;
    const camera = map().camera.length;

    fireEvent.click(chip('Phở'));

    expect(where()).toBe('/map?food=pho');
    expect(chip('Phở')).toHaveAttribute('aria-pressed', 'true');
    expect([1, 2, 3, 4, 5, 6].map(pinState)).toEqual(['match', 'match', 'dim', 'dim', 'dim', 'match']);
    expect([1, 2, 3, 4, 5, 6].map(pin)).toEqual(pinsBefore);           // same elements: nothing recreated
    expect(pin(3).classList.contains('restaurant-pin--dim')).toBe(true);
    expect(apiCalls()).toHaveLength(requests);
    expect(map().camera).toHaveLength(camera);                          // no fly, no fit
  });

  it('changing the food replaces the history entry and makes no request', async () => {
    renderAt('/map');
    await loadMap();
    fireEvent.click(modeRadio('Món'));
    fireEvent.click(chip('Phở'));
    const requests = apiCalls().length;

    fireEvent.click(chip('Bánh mì'));
    expect(where()).toBe('/map?food=banh-mi');
    expect([3, 6].map(pinState)).toEqual(['match', 'match']);
    expect([1, 2].map(pinState)).toEqual(['dim', 'dim']);
    expect(apiCalls()).toHaveLength(requests);

    act(() => navigateTo(-1));
    expect(where()).toBe('/map');                                        // not back to ?food=pho
  });

  it('"Bỏ chọn món" stays in "Món", restores neutral pins and focuses the food\'s chip', async () => {
    renderAt('/map?food=pho');
    await loadMap();
    const requests = apiCalls().length;

    fireEvent.click(screen.getByRole('button', { name: 'Bỏ chọn món' }));

    expect(where()).toBe('/map');
    expect(modeRadio('Món')).toBeChecked();
    expect([1, 2, 3, 5].map(pinState)).toEqual(['food', 'food', 'food', 'plain']);
    await waitFor(() => expect(chip('Phở')).toHaveFocus());
    expect(chip('Phở')).toHaveAttribute('aria-pressed', 'false');
    expect(apiCalls()).toHaveLength(requests);
  });

  it('pressing the chosen chip again clears the food', async () => {
    renderAt('/map?food=pho');
    await loadMap();
    fireEvent.click(chip('Phở'));
    expect(where()).toBe('/map');
    expect(chip('Phở')).toHaveAttribute('aria-pressed', 'false');
    expect(pinState(1)).toBe('food');
  });

  it('back to "Quán" removes the food from the URL without a request', async () => {
    renderAt('/map?food=pho');
    await loadMap();
    const requests = apiCalls().length;

    fireEvent.click(modeRadio('Quán'));

    expect(where()).toBe('/map');
    expect(screen.queryByRole('list', { name: 'Chọn món' })).toBeNull();
    expect([1, 3, 5].map(pinState)).toEqual(['food', 'food', 'plain']);
    expect(apiCalls()).toHaveLength(requests);
  });

  it('the viewport request is the existing one, once per settled view — modes and foods add none', async () => {
    renderAt('/map');
    await loadMap();
    const [viewport] = callsTo('/restaurants/viewport');
    expect(Object.keys(viewport.params).sort()).toEqual(['neLat', 'neLng', 'swLat', 'swLng']);

    fireEvent.click(modeRadio('Món'));
    fireEvent.click(chip('Phở'));
    fireEvent.click(chip('Bún'));
    fireEvent.click(screen.getByRole('button', { name: 'Bỏ chọn món' }));
    fireEvent.click(modeRadio('Quán'));
    await new Promise((r) => setTimeout(r, 800));                        // past the viewport debounce
    expect(callsTo('/restaurants/viewport')).toHaveLength(1);

    // a real pan still asks again, exactly as before
    map().center = { lng: 106.71, lat: 10.78 };
    await act(async () => map().fire('moveend'));
    await waitFor(() => expect(callsTo('/restaurants/viewport')).toHaveLength(2), { timeout: 2000 });
  });
});

// ── Counts and copy ───────────────────────────────────────────────────────────

describe('food counts in the map frame', () => {
  it('each chip counts the loaded restaurants with that food; zero has no badge but stays selectable', async () => {
    renderAt('/map');
    await loadMap();
    fireEvent.click(modeRadio('Món'));

    const badge = (label) => chip(label).querySelector('[data-count-badge]')?.textContent ?? null;
    expect(badge('Phở')).toBe('3');
    expect(badge('Bún')).toBe('2');
    expect(badge('Bánh mì')).toBe('2');
    expect(badge('Cà phê')).toBe('2');
    expect(badge('Cơm')).toBeNull();
    expect(chip('Phở')).toHaveAccessibleName('Phở, 3 quán trong khung bản đồ');
    expect(chip('Cơm')).toHaveAccessibleName('Cơm, chưa có quán trong khung bản đồ');
    expect(chip('Cơm')).not.toBeDisabled();
  });

  it('every count names its scope, and nothing claims popularity', async () => {
    renderAt('/map?food=pho');
    await loadMap();
    expect(document.querySelector('[data-food-count]').textContent).toMatch(/^3 quán có Phở trong khung bản đồ$/);
    expect(screen.getByRole('heading', { level: 3, name: 'Có Phở (3)' })).toBeInTheDocument();
    expect(screen.getByText('Quán khác trong khung (3)')).toBeInTheDocument();
    const text = document.body.textContent.toLowerCase();
    for (const banned of ['phổ biến', 'nhiều người', 'đang xem', 'đang đông', 'trending', 'trực tiếp']) {
      expect(text).not.toContain(banned);
    }
  });

  it('with no restaurant for the food in the frame: honest empty state, zoom out, the food page', async () => {
    renderAt('/map?food=com');
    await loadMap();
    expect(screen.getByText('Khung bản đồ này chưa có quán Cơm nào FoodMap biết.')).toBeInTheDocument();
    expect(screen.queryByText(/Không có quán/)).toBeNull();
    // the header link and the empty state's action, both to the food page
    expect(screen.getAllByRole('link', { name: 'Xem trang món Cơm' }).map((a) => a.getAttribute('href')))
      .toEqual(['/mon/com', '/mon/com']);

    const camera = map().camera.length;
    fireEvent.click(screen.getByRole('button', { name: 'Thu nhỏ bản đồ' }));
    expect(map().camera.slice(camera).map(([name]) => name)).toEqual(['zoomOut']);
  });

  it('announces the settled count once, not "loading" on every pan', async () => {
    renderAt('/map?food=pho');
    await loadMap();
    const announcer = document.querySelector('[data-map-announcer]');
    expect(announcer).toHaveAttribute('aria-live', 'polite');
    await waitFor(() => expect(announcer).toHaveTextContent('3 quán có Phở trong khung bản đồ'), { timeout: 2000 });

    map().center = { lng: 106.71, lat: 10.78 };
    await act(async () => map().fire('moveend'));
    await waitFor(() => expect(callsTo('/restaurants/viewport')).toHaveLength(2), { timeout: 2000 });
    expect(announcer.textContent).not.toMatch(/Đang tải/);
    expect(announcer).toHaveTextContent('3 quán có Phở trong khung bản đồ');
  });
});

// ── Sidebar, cards, popup ─────────────────────────────────────────────────────

describe('food-aware sidebar and cards', () => {
  it('"Món": restaurants with the food first, the others folded; no rating sort', async () => {
    renderAt('/map?food=pho');
    await loadMap();

    const section = screen.getByRole('region', { name: 'Có Phở (3)' });
    expect(within(section).getAllByRole('link').map((a) => a.getAttribute('href')).sort())
      .toEqual(['/restaurants/1', '/restaurants/2', '/restaurants/6']);
    const folded = document.querySelector('[data-other-restaurants]');
    expect(folded.open).toBe(false);
    expect(within(folded).getAllByRole('link', { hidden: true }).map((a) => a.getAttribute('href')).sort())
      .toEqual(['/restaurants/3', '/restaurants/4', '/restaurants/5']);
    expect(within(screen.getByRole('group', { name: 'Sắp xếp' })).getAllByRole('button').map((b) => b.textContent))
      .toEqual(['Phù hợp', 'Gần nhất']);
  });

  it('cards carry the server\'s foods as pills: discoverable only, ≤3 + "+N", the chosen food first, none when empty', async () => {
    renderAt('/map?food=pho');
    await loadMap();
    const card = (id) => document.getElementById(`card-${id}`);
    const pills = (id) => [...card(id).querySelectorAll('[data-food-pill]')].map((p) => p.textContent);

    expect(pills(6)).toEqual(['Phở', 'Bún', 'Bánh mì']);
    expect(within(card(6)).getByLabelText('và 1 món khác')).toHaveTextContent('+1');
    expect(pills(2)).toEqual(['Phở', 'Bún']);
    expect(pills(4)).toEqual(['Cà phê']);                               // "Trà" is classification-only
    expect(card(5).querySelector('[aria-label="Món có ở quán"]')).toBeNull();
    expect(card(5).textContent).not.toMatch(/không rõ/i);
  });

  it('opening a restaurant that has the food passes { fromFood }; one without it passes nothing', async () => {
    renderAt('/map?food=pho');
    await loadMap();
    fireEvent.click(document.querySelector('#card-2 a'));
    expect(where()).toBe('/restaurants/2');
    expect(screen.getByTestId('state')).toHaveTextContent('{"fromFood":"pho"}');

    act(() => navigateTo(-1));
    await waitFor(() => expect(document.querySelector('#card-3 a')).not.toBeNull());
    fireEvent.click(document.querySelector('#card-3 a'));
    expect(where()).toBe('/restaurants/3');
    expect(screen.getByTestId('state')).toHaveTextContent('');
  });

  it('the Food Page link keeps the current scope', async () => {
    renderAt('/map?food=pho');
    await loadMap();
    expect(screen.getByRole('link', { name: 'Xem trang món Phở' })).toHaveAttribute('href', '/mon/pho');
  });

  it('popup: "Có món Phở" and { fromFood } for a match; a non-match never mentions it', async () => {
    renderAt('/map?food=pho');
    await loadMap();

    await act(async () => pin(1).click());
    const popup = await waitFor(() => {
      const node = document.querySelector('[data-popup]');
      expect(node).not.toBeNull();
      return node;
    });
    await waitFor(() => expect(popup.querySelector('[data-food-note]')).toHaveTextContent('Có món Phở'));
    expect([...popup.querySelectorAll('[data-food-pill]')].map((p) => p.textContent)).toEqual(['Phở']);

    await act(async () => pin(3).click());                               // a dimmed dot stays clickable
    await waitFor(() => expect(document.querySelector('[data-popup]')?.textContent).toMatch(/Bánh Mì Hai Lúa/));
    const other = document.querySelector('[data-popup]');
    expect(other.querySelector('[data-food-note]')).toBeNull();
    expect(other.textContent).not.toMatch(/Phở/);
    expect(document.querySelector('[data-other-restaurants]').open).toBe(true);   // its card unfolded
    expect(pinState(3)).toBe('food');                                     // the selected pin is never a dot
    expect(pin(3).classList.contains('restaurant-pin--active')).toBe(true);

    await act(async () => pin(2).click());
    await waitFor(() => expect(document.querySelector('[data-popup]')?.textContent).toMatch(/Bún Phở Cô Ba/));
    fireEvent.click(within(document.querySelector('[data-popup]')).getByRole('button', { name: 'Xem chi tiết' }));
    expect(where()).toBe('/restaurants/2');
    expect(screen.getByTestId('state')).toHaveTextContent('{"fromFood":"pho"}');
  });

  it('a selection without the newly chosen food is let go; one with it stays', async () => {
    renderAt('/map');
    await loadMap();
    await act(async () => pin(2).click());
    fireEvent.click(modeRadio('Món'));
    fireEvent.click(chip('Phở'));
    expect(pin(2).classList.contains('restaurant-pin--active')).toBe(true);
    fireEvent.click(chip('Bánh mì'));
    expect(pin(2).classList.contains('restaurant-pin--active')).toBe(false);
  });
});

// ── Back from a restaurant ────────────────────────────────────────────────────

describe('Back from a restaurant', () => {
  it('restores mode, food, camera and selection from memory: no fly, no pan, no new announcement', async () => {
    renderAt('/map?food=pho');
    await loadMap();
    map().center = { lng: 106.705, lat: 10.779 };
    map().zoom = 16.5;
    await act(async () => map().fire('moveend'));
    await waitFor(() => expect(callsTo('/restaurants/viewport')).toHaveLength(2), { timeout: 2000 });
    await act(async () => pin(2).click());
    const announcer = () => document.querySelector('[data-map-announcer]');
    await waitFor(() => expect(announcer()).toHaveTextContent('3 quán có Phở'), { timeout: 2000 });

    fireEvent.click(document.querySelector('#card-2 a'));
    expect(where()).toBe('/restaurants/2');

    act(() => navigateTo(-1));
    expect(where()).toBe('/map?food=pho');
    const restored = map();
    expect(restored.options.center).toEqual([106.705, 10.779]);
    expect(restored.options.zoom).toBe(16.5);
    expect(modeRadio('Món')).toBeChecked();
    expect(chip('Phở')).toHaveAttribute('aria-pressed', 'true');
    // the restaurants are back at once (no empty flash) and already announced
    expect(pinState(2)).toBe('match');
    expect(pin(2).classList.contains('restaurant-pin--active')).toBe(true);
    expect(announcer()).toHaveTextContent('3 quán có Phở trong khung bản đồ');
    expect(restored.camera.filter(([name]) => name === 'flyTo' || name === 'easeTo')).toEqual([]);
    expect(window.location.search).not.toMatch(/lat|lng|zoom/);
    expect(where()).not.toMatch(/lat|lng|zoom|center|bounds/);
  });

  it('a fresh visit (not Back) starts in "Quán" at the default camera', async () => {
    renderAt('/map?food=pho');
    await loadMap();
    act(() => navigateTo('/restaurants/1'));
    act(() => navigateTo('/map'));
    expect(modeRadio('Quán')).toBeChecked();
    expect(map().options.center).toEqual([106.7009, 10.7769]);
  });
});

// ── Errors and loading ────────────────────────────────────────────────────────

describe('loading and errors', () => {
  it('catalog loading shows rail skeletons; a failure shows one retry notice that recovers', async () => {
    let fail = true;
    let release;
    handlers['/catalog'] = () => new Promise((ok, no) => { release = () => (fail ? no(networkError()) : ok(catalogBody())); });
    renderAt('/map?food=pho');
    expect(document.querySelectorAll('[data-rail-skeleton]')).toHaveLength(6);

    await act(async () => release());
    expect(await screen.findByText('Chưa tải được danh sách món.')).toBeInTheDocument();
    fail = false;
    handlers['/catalog'] = () => catalogBody();
    fireEvent.click(screen.getByRole('button', { name: 'Thử lại' }));
    expect(await screen.findByRole('button', { name: /^Phở,/ })).toHaveAttribute('aria-pressed', 'true');
  });

  it('a viewport 429 keeps the pins and counts down the server\'s wait — one message only', async () => {
    renderAt('/map');
    await loadMap();
    handlers['/restaurants/viewport'] = () => { throw httpError(429, { headers: { 'retry-after': '7' } }); };
    map().center = { lng: 106.71, lat: 10.78 };
    await act(async () => map().fire('moveend'));

    expect(await screen.findByText(/đợi 7 giây/, {}, { timeout: 2000 })).toBeInTheDocument();
    expect(screen.getAllByText('Không tải được danh sách quán')).toHaveLength(1);
    expect(pin(1)).not.toBeNull();
    expect(screen.getByRole('button', { name: 'Thử lại' })).toBeDisabled();
  });

  it('a viewport network error: previous pins stay, one message, "Thử lại" asks again', async () => {
    renderAt('/map');
    await loadMap();
    handlers['/restaurants/viewport'] = () => { throw networkError(); };
    map().center = { lng: 106.71, lat: 10.78 };
    await act(async () => map().fire('moveend'));
    expect(await screen.findByText('Không tải được danh sách quán', {}, { timeout: 2000 })).toBeInTheDocument();
    expect(screen.getAllByText(/Không tải được danh sách quán/)).toHaveLength(1);
    expect(pin(1)).not.toBeNull();

    handlers['/restaurants/viewport'] = () => RESTAURANTS;
    fireEvent.click(screen.getByRole('button', { name: 'Thử lại' }));
    await waitFor(() => expect(callsTo('/restaurants/viewport')).toHaveLength(3));
    await waitFor(() => expect(screen.queryByText('Không tải được danh sách quán')).toBeNull());
  });

  it('never shows the server\'s raw message', async () => {
    handlers['/restaurants/viewport'] = () => { throw httpError(500, { message: 'NullPointerException at DiscoveryService' }); };
    renderAt('/map');
    await act(async () => map().fire('load'));
    expect(await screen.findByText('Không tải được danh sách quán', {}, { timeout: 2000 })).toBeInTheDocument();
    expect(document.body.textContent).not.toMatch(/NullPointerException/);
  });
});

// ── Accessibility ─────────────────────────────────────────────────────────────

describe('accessibility', () => {
  it('the mode control is a native radio group in a fieldset with a legend', () => {
    renderAt('/map');
    const group = screen.getByRole('group', { name: 'Xem bản đồ theo' });
    expect(group.tagName).toBe('FIELDSET');
    expect(within(group).getAllByRole('radio').map((r) => r.type)).toEqual(['radio', 'radio']);
  });

  it('works from the keyboard: radios, chips, clear; focus stays on the control used', async () => {
    const user = userEvent.setup();
    renderAt('/map');
    await loadMap();

    modeRadio('Quán').focus();
    await user.keyboard('{ArrowRight}');
    expect(modeRadio('Món')).toBeChecked();
    expect(modeRadio('Món')).toHaveFocus();

    chip('Phở').focus();
    await user.keyboard('{Enter}');
    expect(where()).toBe('/map?food=pho');
    expect(chip('Phở')).toHaveFocus();

    chip('Bún').focus();
    await user.keyboard(' ');
    expect(where()).toBe('/map?food=bun');
    expect(chip('Bún')).toHaveFocus();
  });

  it('markers are not tab stops; the sidebar is the keyboard path', async () => {
    renderAt('/map?food=pho');
    await loadMap();
    for (const el of document.querySelectorAll('.restaurant-pin')) {
      expect(el.hasAttribute('tabindex')).toBe(false);
      expect(el.tagName).toBe('DIV');
    }
    expect(document.querySelectorAll('#card-1 a')).toHaveLength(1);
  });

  it('match state is not colour alone: matches carry a glyph and size class, others a dot class, and the text says so', async () => {
    renderAt('/map?food=pho');
    await loadMap();
    expect(pin(1).classList.contains('restaurant-pin--match')).toBe(true);
    expect(pin(3).classList.contains('restaurant-pin--dim')).toBe(true);
    const css = readFileSync(resolve(process.cwd(), 'src/index.css'), 'utf8');
    expect(css).toMatch(/\.restaurant-pin--match\s*\{[^}]*width:\s*40px/);
    expect(css).toMatch(/\.restaurant-pin--dim\s*\{[^}]*width:\s*24px/);
    expect(css).toMatch(/\.restaurant-pin--dim \.restaurant-pin__face::after\s*\{[^}]*width:\s*10px/);
    expect(screen.getByRole('heading', { level: 3, name: 'Có Phở (3)' })).toBeInTheDocument();
  });

  it('new controls are at least 44 px tall (min-h-11)', async () => {
    renderAt('/map?food=pho');
    await loadMap();
    const tall = (el) => expect(el.className, el.outerHTML.slice(0, 80)).toMatch(/\bmin-h-11\b/);
    tall(modeRadio('Quán').nextElementSibling);
    tall(modeRadio('Món').nextElementSibling);
    screen.getAllByRole('button').filter((b) => b.dataset.foodChip).forEach(tall);
    tall(screen.getByRole('link', { name: 'Xem trang món Phở' }));
    tall(screen.getByRole('button', { name: 'Bỏ chọn món' }));
    tall(document.querySelector('[data-other-restaurants] summary'));
  });

  it('reduced motion: pins have no transition, and the map moves instantly', async () => {
    const css = readFileSync(resolve(process.cwd(), 'src/index.css'), 'utf8');
    const reduced = css.slice(css.indexOf('@media (prefers-reduced-motion: reduce)'));
    expect(reduced).toMatch(/\.restaurant-pin\s*\{\s*transition:\s*none !important;/);

    vi.stubGlobal('matchMedia', (query) => ({ matches: query.includes('reduce'), addEventListener() {}, removeEventListener() {} }));
    renderAt('/map?food=pho');
    await loadMap();
    await act(async () => pin(1).click());
    expect(map().camera.filter(([name]) => name === 'easeTo').map(([, o]) => o.duration)).toEqual([0]);
    fireEvent.click(screen.getByRole('button', { name: 'Bỏ chọn món' }));
    fireEvent.click(chip('Cơm'));
    fireEvent.click(screen.getByRole('button', { name: 'Thu nhỏ bản đồ' }));
    expect(map().camera.filter(([name]) => name === 'zoomOut').map(([, o]) => o.duration)).toEqual([0]);
  });
});
