import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { MemoryRouter, Route, Routes, useLocation } from 'react-router-dom';
import apiClient from '../../api/client';
import SiteSearch, { SEARCH_INPUT_ID } from './SiteSearch';
import SearchBar from './SearchBar';
import { resetFoodMatchMemo } from '../../api/catalog.api';
import { rememberLocation, resetLocationScope, selectArea } from '../../hooks/useLocationScope';
import { httpError } from '../../test/foodFixtures';

// Only the HTTP client is faked: the real SearchBar, catalog API, scope store and routes.
vi.mock('../../api/client', () => ({ default: { get: vi.fn() } }));

/** What the server's /catalog/foods/match answers (FoodMatcher on the server — not here). */
const MATCH = {
  'phở': { foods: [{ key: 'pho', label: 'Phở' }], intent: { key: 'pho', label: 'Phở' } },
  pho: { foods: [{ key: 'pho', label: 'Phở' }], intent: { key: 'pho', label: 'Phở' } },
  'bún bò': { foods: [{ key: 'bun-bo', label: 'Bún bò' }, { key: 'bun', label: 'Bún' }], intent: { key: 'bun-bo', label: 'Bún bò' } },
  'Phở Hòa Pasteur': { foods: [{ key: 'pho', label: 'Phở' }], intent: null },
  xyz: { foods: [], intent: null },
};
const PHO_HOA = { id: 1, name: 'Phở Hòa Pasteur', address: '260C Pasteur, Quận 3', avgRating: 4.5, reviewCount: 4 };
const RESTAURANTS = { 'Phở Hòa Pasteur': [PHO_HOA], 'phở': [PHO_HOA] };

function Where() {
  const { pathname, search } = useLocation();
  return <p data-testid="where">{decodeURIComponent(pathname + search)}</p>;
}

const renderSearch = () => render(
  <MemoryRouter initialEntries={['/']}>
    <Routes>
      <Route path="/" element={<><SiteSearch /><Where /></>} />
      <Route path="*" element={<Where />} />
    </Routes>
  </MemoryRouter>,
);

const input = () => document.getElementById(SEARCH_INPUT_ID);
const type = (text) => {
  fireEvent.focus(input());
  fireEvent.change(input(), { target: { value: text } });
};
const enter = () => fireEvent.submit(input().closest('form'));
const where = () => screen.getByTestId('where').textContent;
const urls = () => apiClient.get.mock.calls.map(([url]) => url);
const foodSection = async () => (await screen.findByText('Món ăn')).closest('section');

beforeEach(() => {
  apiClient.get.mockReset();
  apiClient.get.mockImplementation(async (url, config) => {
    const q = config?.params?.q ?? config?.params?.query;
    if (url === '/catalog/foods/match') return { data: MATCH[q] ?? { foods: [], intent: null } };
    if (url === '/restaurants/search') return { data: { content: RESTAURANTS[q] ?? [] } };
    if (url === '/foods/search') return { data: { content: [{ id: 7, name: 'Phở Bò Tái', cuisine: 'Vietnamese' }] } };
    throw new Error(`unexpected request ${url}`);
  });
  resetFoodMatchMemo();
  resetLocationScope();
  localStorage.clear();
});
afterEach(() => localStorage.clear());

describe('site search → the food page (food intent)', () => {
  it('"phở": the server\'s food suggestion opens /mon/pho', async () => {
    renderSearch();
    type('phở');
    const foods = await foodSection();
    fireEvent.click(within(foods).getByRole('button', { name: /^Phở/ }));
    expect(where()).toBe('/mon/pho');
    expect(apiClient.get).toHaveBeenCalledWith('/catalog/foods/match', { params: { q: 'phở' } });
  });

  it('"phở" + Enter opens /mon/pho — the server says the whole query names that food', async () => {
    renderSearch();
    type('phở');
    enter();
    await waitFor(() => expect(where()).toBe('/mon/pho'));
  });

  it('"pho" (no accents) is the same food — as the server resolves it', async () => {
    renderSearch();
    type('pho');
    enter();
    await waitFor(() => expect(where()).toBe('/mon/pho'));
  });

  it('"bún bò" → /mon/bun-bo (suggestion or Enter)', async () => {
    renderSearch();
    type('bún bò');
    const foods = await foodSection();
    expect(within(foods).getAllByRole('button').map((b) => b.textContent)).toEqual([
      expect.stringMatching(/^Bún bò/), expect.stringMatching(/^Bún/),
    ]);
    enter();
    await waitFor(() => expect(where()).toBe('/mon/bun-bo'));
  });

  it('suggestions and Enter for the same query cost one food request', async () => {
    renderSearch();
    type('phở');
    await foodSection();
    enter();
    await waitFor(() => expect(where()).toBe('/mon/pho'));
    expect(urls().filter((u) => u === '/catalog/foods/match')).toHaveLength(1);
  });

  it('the scope already chosen in this tab comes along — never coordinates', async () => {
    selectArea('quan-1', 'Quận 1');
    const first = renderSearch();
    type('phở');
    enter();
    await waitFor(() => expect(where()).toBe('/mon/pho?area=quan-1'));
    first.unmount();

    resetLocationScope();
    rememberLocation({ lat: 10.7725, lng: 106.698 });
    renderSearch();
    type('bún bò');
    enter();
    await waitFor(() => expect(where()).toBe('/mon/bun-bo?near=1'));
    expect(where()).not.toMatch(/lat|lng|10\.77|106\.69/);
  });

  it('never asks the legacy dish search', async () => {
    renderSearch();
    type('phở');
    await foodSection();
    expect(urls()).not.toContain('/foods/search');
  });
});

describe('site search — restaurant intent and no food are left as they were', () => {
  it('a restaurant name: the restaurant suggestion opens the restaurant', async () => {
    renderSearch();
    type('Phở Hòa Pasteur');
    const restaurants = (await screen.findByText('Quán ăn')).closest('section');
    fireEvent.click(within(restaurants).getByRole('button', { name: /Phở Hòa Pasteur/ }));
    expect(where()).toBe('/restaurants/1');
  });

  it('a restaurant name + Enter is a text search, not a food page (it only mentions phở)', async () => {
    renderSearch();
    type('Phở Hòa Pasteur');
    enter();
    await waitFor(() => expect(where()).toBe('/map?q=Phở Hòa Pasteur'));
  });

  it('an unknown word: no food is invented — the text search, as before', async () => {
    renderSearch();
    type('xyz');
    expect(await screen.findByText(/Không có kết quả cho/)).toBeInTheDocument();
    enter();
    await waitFor(() => expect(where()).toBe('/map?q=xyz'));
  });

  it('if the food lookup fails, Enter keeps the text search — never a guessed food', async () => {
    apiClient.get.mockImplementation(async (url) => {
      if (url === '/catalog/foods/match') throw httpError(500);
      return { data: { content: [] } };
    });
    renderSearch();
    type('phở');
    enter();
    await waitFor(() => expect(where()).toBe('/map?q=phở'));
  });
});

describe('the map page\'s own SearchBar keeps the dish filter', () => {
  it('lists FoodMap menu dishes from /foods/search and hands them to the map', async () => {
    const onDishSelect = vi.fn();
    render(<MemoryRouter><SearchBar inputId={SEARCH_INPUT_ID} onDishSelect={onDishSelect} onSearch={vi.fn()} /></MemoryRouter>);
    type('phở');
    const foods = await foodSection();
    fireEvent.click(within(foods).getByRole('button', { name: /Phở Bò Tái/ }));
    expect(onDishSelect).toHaveBeenCalledWith(expect.objectContaining({ id: 7, name: 'Phở Bò Tái' }));
    expect(urls()).toContain('/foods/search');
    expect(urls()).not.toContain('/catalog/foods/match');
  });
});
