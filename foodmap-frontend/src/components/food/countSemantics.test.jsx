import { describe, expect, it } from 'vitest';
import { render, screen } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import FoodChip, { countText } from './FoodChip';
import FoodReveal, { supplyLine } from './FoodReveal';
import { normalizeAvailableFoods, normalizeFoodDecision, normalizeFoodRestaurants } from '../../api/decision.api';
import { CITY_SCOPE } from '../../test/foodFixtures';
import { PHO, foodDecision } from '../../test/foodDecisionFixtures';

// Counts are exact unless the SERVER says they are a lower bound (its rows hit
// a safety cap): then "N+ quán". Nothing is inferred from the number itself.
const chip = (count, countCapped) => ({
  key: 'pho', label: 'Phở', kind: 'MEAL', parentCategory: 'noodle', tone: 'chili',
  count, countCapped, radiusMeters: 3000, radiusWidened: false,
});

describe('countText — exact or "at least", as the server says', () => {
  it('exact counts are plain; a lower bound gets "+"', () => {
    expect(countText(3)).toBe('3 quán');
    expect(countText(3, false)).toBe('3 quán');
    expect(countText(3, true)).toBe('3+ quán');
    expect(countText(99, true)).toBe('99+ quán');
  });

  it('the chip\'s display limit (99+, spec §9) applies to both — it says nothing untrue', () => {
    expect(countText(127, false)).toBe('99+ quán');
    expect(countText(2000, true)).toBe('99+ quán');
  });

  it('no inference from the number: 2000 exact stays exact, 5 capped is "5+"', () => {
    expect(countText(5, true)).toBe('5+ quán');
    expect(countText(42, false)).toBe('42 quán');
  });
});

describe('"Thèm gì?" chips use the server\'s countCapped', () => {
  it('exact', () => {
    render(<MemoryRouter><ul><FoodChip food={chip(3, false)} href="/mon/pho" scopePhrase="quanh trung tâm TP.HCM" /></ul></MemoryRouter>);
    expect(screen.getByRole('link', { name: 'Phở, 3 quán quanh trung tâm TP.HCM' })).toBeInTheDocument();
  });

  it('capped: "3+ quán", in the visible text and the accessible name', () => {
    render(<MemoryRouter><ul><FoodChip food={chip(3, true)} href="/mon/pho" scopePhrase="quanh trung tâm TP.HCM" /></ul></MemoryRouter>);
    const link = screen.getByRole('link', { name: 'Phở, 3+ quán quanh trung tâm TP.HCM' });
    expect(link).toHaveTextContent('3+ quán');
  });
});

describe('the cà mèn reveal uses restaurantCountCapped', () => {
  it('exact and capped supply lines', () => {
    expect(supplyLine(normalizeFoodDecision(foodDecision(PHO, { restaurantCount: 10 })))).toBe('10 quán quanh trung tâm TP.HCM');
    const capped = normalizeFoodDecision(foodDecision(PHO, { restaurantCount: 10, restaurantCountCapped: true }));
    expect(supplyLine(capped)).toBe('10+ quán quanh trung tâm TP.HCM');
    render(<FoodReveal decision={capped} animate={false} />);
    expect(screen.getByText('10+ quán quanh trung tâm TP.HCM')).toBeInTheDocument();
  });
});

describe('the API layer keeps each response\'s own flag', () => {
  it('available foods: countCapped per food; absent means exact', () => {
    const n = normalizeAvailableFoods({ scope: CITY_SCOPE, foods: [chip(2000, true), { ...chip(3), key: 'bun', label: 'Bún' }] });
    expect(n.foods.map((f) => [f.count, f.countCapped])).toEqual([[2000, true], [3, false]]);
  });

  it('food decision: restaurantCountCapped; food page list: totalCapped', () => {
    expect(normalizeFoodDecision(foodDecision(PHO, { restaurantCountCapped: true })).restaurantCountCapped).toBe(true);
    expect(normalizeFoodDecision(foodDecision(PHO)).restaurantCountCapped).toBe(false);
    expect(normalizeFoodRestaurants({ totalElements: 2000, totalCapped: true, items: [] }))
      .toMatchObject({ totalElements: 2000, totalCapped: true });
    expect(normalizeFoodRestaurants({ totalElements: 2000, items: [] }).totalCapped).toBe(false);
  });
});
