import { beforeEach, describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import apiClient from '../api/client';
import RestaurantDetailPage from './RestaurantDetailPage';
import { BANH_MI } from '../test/decisionFixtures';
import { resetCatalogCache } from '../hooks/useCatalog';
import { rememberLocation, resetLocationScope, selectArea } from '../hooks/useLocationScope';

vi.mock('../api/client', () => ({ default: { get: vi.fn() } }));
vi.mock('../hooks/useAuth', () => ({ useAuth: () => ({ isAuthenticated: false, user: null }) }));

function renderAt(entries) {
  return render(
    <MemoryRouter initialEntries={entries} initialIndex={entries.length - 1}>
      <Routes>
        <Route path="/" element={<p>Trang chủ (test)</p>} />
        <Route path="/map" element={<p>Bản đồ (test)</p>} />
        <Route path="/restaurants/:id" element={<RestaurantDetailPage />} />
      </Routes>
    </MemoryRouter>,
  );
}

beforeEach(() => {
  apiClient.get.mockReset();
  apiClient.get.mockImplementation(async (url) => (url.endsWith('/reviews')
    ? { data: { content: [], totalElements: 0, totalPages: 0, last: true, page: 0, size: 10 } }
    : { data: { ...BANH_MI, restaurantFoods: [] } }));
});

describe('RestaurantDetailPage — the way back', () => {
  it('returns to where the user came from (e.g. the homepage suggestion)', async () => {
    renderAt(['/', '/restaurants/4']);
    await screen.findByRole('heading', { level: 1, name: BANH_MI.name }); // loaded, not the skeleton
    const back = screen.getByRole('button', { name: 'Quay lại' });
    expect(screen.queryByRole('link', { name: 'Về bản đồ' })).not.toBeInTheDocument();
    fireEvent.click(back);
    expect(await screen.findByText('Trang chủ (test)')).toBeInTheDocument();
  });

  it('offers the map when the page was opened directly', async () => {
    renderAt(['/restaurants/4']);
    await screen.findByRole('heading', { level: 1, name: BANH_MI.name });
    const link = screen.getByRole('link', { name: 'Về bản đồ' });
    expect(link).toHaveAttribute('href', '/map');
  });
});

describe('RestaurantDetailPage — "Chỉ đường"', () => {
  const useRestaurant = (restaurant) => apiClient.get.mockImplementation(async (url) => (url.endsWith('/reviews')
    ? { data: { content: [], totalElements: 0, totalPages: 0, last: true, page: 0, size: 10 } }
    : { data: { ...restaurant, restaurantFoods: [] } }));

  it('valid coordinates: directions to the restaurant\'s own coordinates; no location request, no extra API call', async () => {
    const geo = { getCurrentPosition: vi.fn(), watchPosition: vi.fn() };
    vi.stubGlobal('navigator', { ...navigator, geolocation: geo });
    renderAt(['/restaurants/4']);
    await screen.findByRole('heading', { level: 1, name: BANH_MI.name });
    const link = screen.getByRole('link', { name: `Chỉ đường đến ${BANH_MI.name}` });
    expect(link).toHaveAttribute('href', 'https://www.google.com/maps/dir/?api=1&destination=10.7764%2C106.7008');
    expect(link).toHaveAttribute('target', '_blank');
    expect(link).toHaveAttribute('rel', 'noopener noreferrer');
    expect(link.className).toMatch(/\bh-11\b/);   // 44 px
    expect(geo.getCurrentPosition).not.toHaveBeenCalled();
    expect(geo.watchPosition).not.toHaveBeenCalled();
    expect(apiClient.get.mock.calls.every(([url]) => url.startsWith('/restaurants/4'))).toBe(true);
    vi.unstubAllGlobals();
  });

  it.each([
    ['missing latitude', { latitude: undefined }],
    ['missing longitude', { longitude: undefined }],
    ['invalid latitude', { latitude: -95 }],
    ['invalid longitude', { longitude: 200 }],
  ])('%s: no "Chỉ đường"; the page is otherwise the same', async (_, broken) => {
    useRestaurant({ ...BANH_MI, ...broken });
    renderAt(['/restaurants/4']);
    await screen.findByRole('heading', { level: 1, name: BANH_MI.name });
    expect(screen.queryByRole('link', { name: /^Chỉ đường/ })).toBeNull();
    expect(screen.getByText(BANH_MI.address)).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Về bản đồ' })).toHaveAttribute('href', '/map');
  });
});

describe('RestaurantDetailPage — "Quán này có" (the server\'s food classification)', () => {
  // The server's catalog: labels, art and discoverability are its own. 'tra' is classification-only.
  const CATALOG = {
    version: 'test',
    foods: [
      { key: 'pho', label: 'Phở (nhãn máy chủ)', kind: 'MEAL', parentCategory: 'noodle', tone: 'chili', discoverable: true, order: 50, artKey: 'pho' },
      { key: 'bun-bo', label: 'Bún bò', kind: 'MEAL', parentCategory: 'noodle', tone: 'chili', discoverable: true, order: 40, artKey: 'bun-bo' },
      { key: 'tra', label: 'Trà', kind: 'DRINK', parentCategory: 'coffee', tone: 'taro', discoverable: false, order: null, artKey: 'tra' },
    ],
    areas: [{ key: 'binh-thanh', label: 'Bình Thạnh', order: 60 }],
  };
  let catalogCalls;

  const serve = (restaurant, { catalog = () => CATALOG } = {}) => apiClient.get.mockImplementation(async (url) => {
    if (url === '/catalog') { catalogCalls += 1; return { data: catalog() }; }
    if (url.endsWith('/reviews')) return { data: { content: [], totalElements: 0, totalPages: 0, last: true, page: 0, size: 10 } };
    return { data: { ...BANH_MI, restaurantFoods: [], ...restaurant } };
  });
  const section = () => screen.queryByRole('heading', { level: 2, name: 'Quán này có' })?.closest('section') ?? null;
  const foodLinks = () => within(section()).getAllByRole('link');
  const loaded = () => screen.findByRole('heading', { level: 1, name: BANH_MI.name });

  beforeEach(() => {
    catalogCalls = 0;
    resetCatalogCache();
    resetLocationScope();
  });

  it('shows the matched foods with the server\'s labels, in the server\'s order, each linking to /mon/{key}', async () => {
    serve({ foodKeys: ['bun-bo', 'pho'] });
    renderAt(['/restaurants/4']);
    await loaded();
    await screen.findByRole('heading', { level: 2, name: 'Quán này có' });
    expect(foodLinks().map((a) => [a.textContent, a.getAttribute('href')])).toEqual([
      ['Bún bò', '/mon/bun-bo'],
      ['Phở (nhãn máy chủ)', '/mon/pho'],
    ]);
    expect(within(section()).getByText('Theo tên quán và thực đơn trên FoodMap.')).toBeInTheDocument();
    expect(section().querySelector('[data-food-art="bun-bo"]')).toHaveAttribute('aria-hidden', 'true');
    expect(foodLinks().every((a) => /\bmin-h-11\b/.test(a.className))).toBe(true);   // 44 px targets
  });

  it('a food without a food page (classification-only) is not listed', async () => {
    serve({ foodKeys: ['pho', 'tra'] });
    renderAt(['/restaurants/4']);
    await screen.findByRole('heading', { level: 2, name: 'Quán này có' });
    expect(foodLinks().map((a) => a.getAttribute('href'))).toEqual(['/mon/pho']);
  });

  it.each([
    ['no foodKeys', {}],
    ['empty foodKeys', { foodKeys: [] }],
  ])('%s: no section, and no catalog request', async (_, restaurant) => {
    serve(restaurant);
    renderAt(['/restaurants/4']);
    await loaded();
    expect(section()).toBeNull();
    expect(catalogCalls).toBe(0);
  });

  it('only foods without a food page, or a key the catalog does not know: the section is hidden', async () => {
    serve({ foodKeys: ['tra', 'khong-co'] });
    renderAt(['/restaurants/4']);
    await loaded();
    await waitFor(() => expect(catalogCalls).toBe(1));
    expect(section()).toBeNull();
  });

  it('the catalog cannot be loaded: the section is hidden, the page is unchanged', async () => {
    serve({ foodKeys: ['pho'] }, { catalog: () => { throw new Error('down'); } });
    renderAt(['/restaurants/4']);
    await loaded();
    await waitFor(() => expect(catalogCalls).toBe(1));
    expect(section()).toBeNull();
    expect(screen.getByRole('link', { name: `Chỉ đường đến ${BANH_MI.name}` })).toBeInTheDocument();
  });

  it('arriving from a food page: that food first, marked and named', async () => {
    serve({ foodKeys: ['pho', 'bun-bo'] });
    renderAt([{ pathname: '/restaurants/4', state: { fromFood: 'bun-bo' } }]);
    await screen.findByRole('heading', { level: 2, name: 'Quán này có' });
    const [first, second] = foodLinks();
    expect(first).toHaveTextContent('Bún bò');
    expect(first).toHaveAttribute('aria-current', 'true');
    expect(second).not.toHaveAttribute('aria-current');
    expect(within(section()).getByText('Bạn đến từ món bún bò · theo tên quán và thực đơn trên FoodMap.')).toBeInTheDocument();
  });

  it('a "from" food the server did not match is ignored — nothing is invented', async () => {
    serve({ foodKeys: ['pho'] });
    renderAt([{ pathname: '/restaurants/4', state: { fromFood: 'bun-bo' } }]);
    await screen.findByRole('heading', { level: 2, name: 'Quán này có' });
    expect(foodLinks().map((a) => a.textContent)).toEqual(['Phở (nhãn máy chủ)']);
    expect(foodLinks()[0]).not.toHaveAttribute('aria-current');
    expect(screen.queryByText(/Bạn đến từ món/)).toBeNull();
  });

  it('links keep the current scope (?area= / ?near=1) and never carry coordinates', async () => {
    serve({ foodKeys: ['pho'] });
    selectArea('binh-thanh', 'Bình Thạnh');
    const { unmount } = renderAt(['/restaurants/4']);
    await screen.findByRole('heading', { level: 2, name: 'Quán này có' });
    expect(foodLinks()[0]).toHaveAttribute('href', '/mon/pho?area=binh-thanh');
    unmount();

    resetLocationScope();
    rememberLocation({ lat: 10.7725, lng: 106.698 });
    renderAt(['/restaurants/4']);
    await screen.findByRole('heading', { level: 2, name: 'Quán này có' });
    expect(foodLinks()[0]).toHaveAttribute('href', '/mon/pho?near=1');
    expect(foodLinks()[0].getAttribute('href')).not.toMatch(/10\.77|106\.69|lat|lng/);
  });
});
