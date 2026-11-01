import { describe, expect, it } from 'vitest';
import { CATEGORIES, categoryOf, isKnownCategory } from './category';

// The server classifies restaurants (Part 2 FoodClassifier); the frontend only
// presents the `category` key it sends.
describe('categoryOf — the server\'s category, never inferred', () => {
  it('maps the server key to its presentation', () => {
    expect(categoryOf({ name: 'Phở Hòa Pasteur', category: 'noodle' })).toBe(CATEGORIES.noodle);
    expect(categoryOf({ name: 'Nhà Hàng Ngon', category: 'vietnamese' }).label).toBe('Món Việt');
  });

  it('ignores the name: what the server says wins', () => {
    expect(categoryOf({ name: 'Phở Hòa Pasteur', category: 'japanese' })).toBe(CATEGORIES.japanese);
    expect(categoryOf({ name: 'Sushi Masa', cuisineType: 'Japanese' })).toBe(CATEGORIES.other);   // no server category
  });

  it('missing, unknown or hostile keys are "other", which is never labelled', () => {
    for (const r of [null, undefined, {}, { category: null }, { category: 'tacos' }, { category: 'toString' },
      { category: '__proto__' }, { category: 42 }]) {
      expect(categoryOf(r)).toBe(CATEGORIES.other);
    }
    expect(isKnownCategory(categoryOf({}))).toBe(false);
    expect(isKnownCategory(categoryOf({ category: 'seafood' }))).toBe(true);
  });
});
