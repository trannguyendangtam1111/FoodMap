import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { describe, expect, it } from 'vitest';
import { render } from '@testing-library/react';
import FoodArt from './FoodArt';
import FoodGlyph from './FoodGlyph';
import { FOOD_ART_KEYS, getFoodArt } from './foodArtRegistry';
import { CYAN } from './primitives';

/** The discoverable food keys of the server catalog itself (no copy kept here). */
function discoverableKeys() {
  // vitest runs from foodmap-frontend/; the catalog lives in the sibling backend
  const yml = readFileSync(resolve(process.cwd(), '../foodmap-backend/src/main/resources/catalog/food-catalog.yml'), 'utf8');
  const keys = [];
  let current = null;
  for (const line of yml.split(/\r?\n/)) {
    const key = line.match(/^\s*- key:\s*(\S+)/);
    if (key) current = key[1];
    if (current && /^\s*discoverable:\s*true\b/.test(line)) keys.push(current);
  }
  return keys;
}

const BRAND_TONED = new Set(['hai-san', 'dimsum']);
const SHAPES = 'path, rect, circle, ellipse, line, polyline, polygon';

const renderArt = (props) => render(<FoodArt className="h-16 w-16" {...props} />).container;

describe('FoodArt registry', () => {
  it('has a drawing for every discoverable catalog food — and only for those', () => {
    const keys = discoverableKeys();
    expect(keys).toHaveLength(20);
    expect([...FOOD_ART_KEYS].sort()).toEqual([...keys].sort());
  });

  it('no two foods share a drawing (no duplicate silhouettes)', () => {
    const components = FOOD_ART_KEYS.map(getFoodArt);
    expect(new Set(components).size).toBe(FOOD_ART_KEYS.length);
    const markup = FOOD_ART_KEYS.map((key) => renderArt({ artKey: key }).querySelector('svg').innerHTML);
    expect(new Set(markup).size).toBe(FOOD_ART_KEYS.length);
    expect(new Set(FOOD_ART_KEYS).size).toBe(FOOD_ART_KEYS.length);
  });

  it('unknown and prototype-like keys resolve to nothing', () => {
    for (const key of ['unknown', 'constructor', '__proto__', 'toString', '', null, undefined, 42]) {
      expect(getFoodArt(key)).toBeNull();
    }
  });
});

describe('FoodArt drawing contract', () => {
  it.each(FOOD_ART_KEYS)('%s: 64×64, decorative, outline token, currentColor, one cyan accent, ≤ 12 shapes, no text', (key) => {
    const container = renderArt({ artKey: key });
    const wrapper = container.firstElementChild;
    const svg = container.querySelector('svg');

    expect(wrapper).toHaveAttribute('aria-hidden', 'true');
    expect(wrapper).toHaveAttribute('data-food-art', key);
    expect(svg).toHaveAttribute('viewBox', '0 0 64 64');
    expect(svg).toHaveAttribute('stroke', 'rgb(var(--fm-art-outline))');
    expect(svg).toHaveAttribute('stroke-width', '2.5');
    expect(svg).toHaveAttribute('fill', 'none');

    const shapes = [...svg.querySelectorAll(SHAPES)];
    expect(shapes.length).toBeGreaterThan(2);
    expect(shapes.length).toBeLessThanOrEqual(12);
    expect(shapes.some((s) => s.getAttribute('fill') === 'currentColor')).toBe(true);

    const cyan = shapes.filter((s) => s.getAttribute('fill') === CYAN || s.getAttribute('stroke') === CYAN);
    // brand-toned foods draw their accent in currentColor (their tone IS the brand cyan)
    expect(cyan).toHaveLength(BRAND_TONED.has(key) ? 0 : 1);

    expect(svg.querySelector('text, image, foreignObject, linearGradient, radialGradient, filter, use')).toBeNull();
    expect(svg.innerHTML).not.toMatch(/url\(|data:|http/);
  });

  it('takes the food tone and the caller’s size/shape classes', () => {
    const wrapper = renderArt({ artKey: 'pho', tone: 'chili', className: 'h-20 w-20 rounded-full' }).firstElementChild;
    expect(wrapper.className).toMatch(/\bbg-chili-tint\b/);
    expect(wrapper.className).toMatch(/\btext-chili\b/);
    expect(wrapper.className).toMatch(/\bh-20\b.*\bw-20\b.*\brounded-full\b/);
  });

  it('without a tone it uses the parent category’s tone', () => {
    const wrapper = renderArt({ artKey: 'kem', parentCategory: 'dessert' }).firstElementChild;
    expect(wrapper.className).toMatch(/\bbg-dragonfruit-tint\b/);
  });
});

describe('FoodArt fallback', () => {
  it('an unknown food falls back to its parent category’s CategoryArt, in the food’s tone', () => {
    const container = renderArt({ artKey: 'banh-canh', parentCategory: 'noodle', tone: 'taro' });
    const wrapper = container.firstElementChild;
    expect(container.querySelector('[data-food-art]')).toBeNull();      // never pretends to be a drawn food
    expect(wrapper).toHaveAttribute('aria-hidden', 'true');
    expect(wrapper.className).toMatch(/\bbg-taro-tint\b/);
    expect(container.querySelector('svg')).not.toBeNull();
  });

  it('an unknown food and unknown category fall back to CategoryArt "other" — never empty, never a crash', () => {
    for (const props of [{ artKey: 'x', parentCategory: 'nope' }, { artKey: undefined }, {}, { artKey: 'pho', tone: 'neon' }]) {
      const container = renderArt(props);
      expect(container.querySelector('svg')).not.toBeNull();
      expect(container.firstElementChild).toHaveAttribute('aria-hidden', 'true');
    }
  });
});

describe('FoodGlyph', () => {
  const inSvg = (glyph) => render(<svg viewBox="0 0 200 200">{glyph}</svg>).container;

  it('is the very same drawing as FoodArt, as a bare <g> placed and scaled in the host SVG', () => {
    const glyph = inSvg(<FoodGlyph artKey="pho" x={10} y={20} size={32} tone="chili" />).querySelector('g');
    expect(glyph).toHaveAttribute('data-food-glyph', 'pho');
    expect(glyph).toHaveAttribute('transform', 'translate(10 20) scale(0.5)');
    expect(glyph).toHaveAttribute('aria-hidden', 'true');
    expect(glyph).toHaveAttribute('stroke', 'rgb(var(--fm-art-outline))');
    expect(glyph.style.color).toBe('rgb(var(--fm-chili))');
    const art = renderArt({ artKey: 'pho' }).querySelector('svg');
    expect(glyph.innerHTML).toBe(art.innerHTML);
  });

  it('works for every food, and an unknown key renders the neutral covered dish', () => {
    for (const key of FOOD_ART_KEYS) {
      expect(inSvg(<FoodGlyph artKey={key} />).querySelector(`g[data-food-glyph="${key}"]`)).not.toBeNull();
    }
    const unknown = inSvg(<FoodGlyph artKey="khong-co" />).querySelector('g');
    expect(unknown).toHaveAttribute('data-food-glyph', 'covered-dish');
    expect(unknown.querySelectorAll(SHAPES).length).toBeGreaterThan(0);
    expect(unknown.style.color).toBe('');                          // no tone → inherits the host's colour
  });
});
