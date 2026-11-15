import { describe, it, expect } from 'vitest';
import { act, render, screen } from '@testing-library/react';
import { setLang } from './index';
import { foodName } from './foodNames';
import FoodPills from '../components/map/FoodPills';
import MapFoodRail from '../components/map/MapFoodRail';
import FoodChip from '../components/food/FoodChip';
import FoodReveal from '../components/food/FoodReveal';
import { MemoryRouter } from 'react-router-dom';

// The map's food pills and rail: generic food types read in English in EN mode
// (by the server's stable catalog key); named dishes keep the catalog label.
const FOODS = [
  { key: 'ca-phe', label: 'Cà phê', discoverable: true, tone: 'taro' },
  { key: 'che', label: 'Chè', discoverable: true, tone: 'dragonfruit' },
  { key: 'bun-bo', label: 'Bún bò', discoverable: true, tone: 'chili' },
];
const BY_KEY = new Map(FOODS.map((f) => [f.key, f]));

describe('foodName', () => {
  it('English name for generic catalog foods in EN, the server label otherwise', () => {
    act(() => setLang('en'));
    expect(foodName(BY_KEY.get('ca-phe'))).toBe('Coffee');
    expect(foodName(BY_KEY.get('che'))).toBe('Sweets');
    expect(foodName(BY_KEY.get('bun-bo'))).toBe('Bún bò');                      // a named dish
    expect(foodName({ key: 'unknown', label: 'Món mới' })).toBe('Món mới');     // not listed → server label
    expect(foodName(null)).toBe('');
    act(() => setLang('vi'));
    expect(foodName(BY_KEY.get('ca-phe'))).toBe('Cà phê');
  });
});

describe('map food labels follow the UI language', () => {
  it('FoodPills: EN names, back to the catalog labels on a live switch to VI', () => {
    act(() => setLang('en'));
    render(<FoodPills foodKeys={['ca-phe', 'che', 'bun-bo']} foods={BY_KEY} />);
    const pills = () => screen.getAllByRole('listitem').map((li) => li.textContent);
    expect(pills()).toEqual(['Coffee', 'Sweets', 'Bún bò']);
    act(() => setLang('vi'));
    expect(pills()).toEqual(['Cà phê', 'Chè', 'Bún bò']);
  });

  it('MapFoodRail: chip text and accessible names use the same names', () => {
    act(() => setLang('en'));
    render(
      <MapFoodRail status="ready" foods={FOODS} counts={new Map([['ca-phe', 3]])} selectedKey={null} onSelect={() => {}} />,
    );
    expect(screen.getByRole('button', { name: 'Coffee, 3 places in the map frame' })).toHaveTextContent('Coffee');
    expect(screen.getByRole('button', { name: 'Sweets, no places in the map frame' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Bún bò, no places in the map frame' })).toBeInTheDocument();
    act(() => setLang('vi'));
    expect(screen.getByRole('button', { name: 'Cà phê, 3 quán trong khung bản đồ' })).toHaveTextContent('Cà phê');
  });
});

describe('home: the "Craving something?" chip and the food result', () => {
  const COFFEE = { key: 'ca-phe', label: 'Cà phê', kind: 'DRINK', parentCategory: 'coffee', tone: 'taro', count: 71, countCapped: false, radiusMeters: 3000, radiusWidened: false };

  it('FoodChip: text and accessible name in the UI language', () => {
    act(() => setLang('en'));
    render(<MemoryRouter><ul><FoodChip food={COFFEE} href="/mon/ca-phe" /></ul></MemoryRouter>);
    const link = screen.getByRole('link', { name: /^Coffee, 71 places/ });
    expect(link).toHaveAttribute('href', '/mon/ca-phe');
    expect(link).toHaveTextContent('Coffee');
    act(() => setLang('vi'));
    expect(screen.getByRole('link', { name: /^Cà phê, 71 quán/ })).toHaveTextContent('Cà phê');
  });

  it('FoodReveal: the food headline in the UI language', () => {
    act(() => setLang('en'));
    const decision = { food: COFFEE, restaurantCount: 71, restaurantCountCapped: false, radiusMeters: 3000, radiusWidened: false, scope: { locationSource: 'CITY_CENTER', label: 'Quanh trung tâm TP.HCM' } };
    render(<FoodReveal decision={decision} />);
    expect(screen.getByRole('heading', { name: 'Coffee' })).toBeInTheDocument();
    act(() => setLang('vi'));
    expect(screen.getByRole('heading', { name: 'Cà phê' })).toBeInTheDocument();
  });
});
