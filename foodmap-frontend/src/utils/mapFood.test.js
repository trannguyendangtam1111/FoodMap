import { describe, expect, it } from 'vitest';
import { countFoods, foodCountText, matchesFood, pillFoods, pinAppearance } from './mapFood';

const foods = new Map([
  ['pho', { key: 'pho', label: 'Phở', discoverable: true }],
  ['bun', { key: 'bun', label: 'Bún', discoverable: true }],
  ['banh-mi', { key: 'banh-mi', label: 'Bánh mì', discoverable: true }],
  ['ca-phe', { key: 'ca-phe', label: 'Cà phê', discoverable: true }],
  ['tra', { key: 'tra', label: 'Trà', discoverable: false }],
]);

describe('matchesFood — the server\'s foodKeys only', () => {
  it('matches a key at any position', () => {
    expect(matchesFood({ foodKeys: ['pho'] }, 'pho')).toBe(true);
    expect(matchesFood({ foodKeys: ['bun', 'pho'] }, 'pho')).toBe(true);
  });

  it('never matches by name, and not without foodKeys or a food', () => {
    expect(matchesFood({ name: 'Phở Hòa', foodKeys: [] }, 'pho')).toBe(false);
    expect(matchesFood({ name: 'Phở Hòa' }, 'pho')).toBe(false);
    expect(matchesFood({ foodKeys: ['pho'] }, null)).toBe(false);
  });
});

describe('countFoods', () => {
  it('counts each restaurant once per food, in one pass over the payload', () => {
    const counts = countFoods([
      { foodKeys: ['pho'] },
      { foodKeys: ['bun', 'pho'] },
      { foodKeys: ['pho', 'pho'] },
      { name: 'Phở không phân loại' },
      { foodKeys: null },
    ]);
    expect(counts.get('pho')).toBe(3);
    expect(counts.get('bun')).toBe(1);
    expect(counts.has('com')).toBe(false);
  });

  it('is empty for no restaurants', () => {
    expect(countFoods([]).size).toBe(0);
    expect(countFoods(undefined).size).toBe(0);
  });
});

describe('pillFoods', () => {
  it('keeps discoverable foods in server order, at most three plus a count', () => {
    const { shown, more } = pillFoods(['bun', 'tra', 'pho', 'banh-mi', 'ca-phe'], foods);
    expect(shown.map((f) => f.key)).toEqual(['bun', 'pho', 'banh-mi']);
    expect(more).toBe(1);
  });

  it('puts the selected food first when the restaurant has it', () => {
    expect(pillFoods(['bun', 'pho'], foods, 'pho').shown.map((f) => f.key)).toEqual(['pho', 'bun']);
    expect(pillFoods(['bun'], foods, 'pho').shown.map((f) => f.key)).toEqual(['bun']);
  });

  it('shows nothing without catalog, keys or discoverable foods', () => {
    expect(pillFoods(['pho'], null).shown).toEqual([]);
    expect(pillFoods([], foods).shown).toEqual([]);
    expect(pillFoods(['tra', 'unknown'], foods).shown).toEqual([]);
  });
});

describe('pinAppearance', () => {
  it('without a food: the primary food\'s glyph, or the plain pin', () => {
    expect(pinAppearance({ foodKeys: ['bun', 'pho'] })).toEqual({ state: 'food', glyphKey: 'bun' });
    expect(pinAppearance({ foodKeys: [] })).toEqual({ state: 'plain', glyphKey: null });
  });

  it('with a food: matches show that food, the others are dimmed', () => {
    expect(pinAppearance({ foodKeys: ['bun', 'pho'] }, { foodKey: 'pho' })).toEqual({ state: 'match', glyphKey: 'pho' });
    expect(pinAppearance({ foodKeys: ['bun'] }, { foodKey: 'pho' })).toEqual({ state: 'dim', glyphKey: null });
    expect(pinAppearance({ name: 'Phở' }, { foodKey: 'pho' })).toEqual({ state: 'dim', glyphKey: null });
  });

  it('never dims the selected restaurant', () => {
    expect(pinAppearance({ foodKeys: ['bun'] }, { foodKey: 'pho', selected: true })).toEqual({ state: 'food', glyphKey: 'bun' });
    expect(pinAppearance({}, { foodKey: 'pho', selected: true })).toEqual({ state: 'plain', glyphKey: null });
  });
});

describe('foodCountText', () => {
  it('always names the map frame', () => {
    expect(foodCountText(4, 'Phở')).toBe('4 quán có Phở trong khung bản đồ');
    expect(foodCountText(0, 'Phở')).toMatch(/trong khung bản đồ$/);
  });
});
