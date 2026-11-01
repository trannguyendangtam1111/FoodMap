import { readdirSync, readFileSync } from 'node:fs';
import { join, resolve } from 'node:path';
import { describe, expect, it } from 'vitest';

// Static guards for the food-aware /map (P1). The map shows the server's food
// facts only: no randomness, no food vocabulary of its own, no claims of
// popularity or activity, and nothing about where the map is looking leaves
// the tab (no URL coordinates, no storage). Directions links keep their own
// explicit exception (components/restaurant/DirectionsLink, FoodPage guard).

const SRC = resolve(process.cwd(), 'src');
const read = (path) => readFileSync(path, 'utf8');
// Code and copy only: block and line comments removed (not the // of a URL).
const code = (path) => read(path).replace(/\/\*[\s\S]*?\*\//g, '').replace(/(^|[^:'"`])\/\/.*$/gm, '$1');

const MAP_FILES = [
  'pages/MapPage.jsx',
  'hooks/useViewportRestaurants.js',
  'hooks/useMediaQuery.js',
  'utils/mapFood.js',
  'components/restaurant/RestaurantCard.jsx',
  ...readdirSync(join(SRC, 'components/map'))
    .filter((name) => /\.(jsx?)$/.test(name) && !/\.test\./.test(name))
    .map((name) => `components/map/${name}`),
].map((file) => join(SRC, file));

const CATALOG = read(resolve(process.cwd(), '../foodmap-backend/src/main/resources/catalog/food-catalog.yml'));
const catalogKeys = () => [...CATALOG.matchAll(/^\s*- key:\s*(\S+)/gm)].map((m) => m[1]);
const catalogLabels = () => [...CATALOG.matchAll(/^\s*label:\s*(.+?)\s*$/gm)].map((m) => m[1]);

// User-facing claims the map must never make (case-insensitive).
const BANNED = [
  /\btrending\b/i, /\bhot\b/i, /phổ biến nhất/i, /nhiều người/i, /đang xem/i, /đang đông/i,
  /\blive\b/i, /trực tiếp/i, /mới mở/i, /khai trương/i,
];

describe('food-aware map — source guards', () => {
  it('covers every map module', () => {
    expect(MAP_FILES.length).toBeGreaterThanOrEqual(10);
    expect(MAP_FILES.some((f) => f.endsWith('MapFoodRail.jsx'))).toBe(true);
  });

  it('no randomness', () => {
    for (const file of MAP_FILES) {
      expect(read(file), file).not.toMatch(/Math\.random|getRandomValues|randomUUID/);
    }
  });

  it('no food keys, labels or keyword lists of its own — classification is the server\'s', () => {
    const keys = catalogKeys();
    const labels = catalogLabels();
    expect(keys.length).toBeGreaterThan(30);
    for (const file of MAP_FILES) {
      const source = read(file);
      for (const key of keys) {
        expect(source, `${file} hard-codes the food key '${key}'`).not.toMatch(new RegExp(`['"\`]${key}['"\`]`));
      }
      for (const label of labels) {
        expect(source, `${file} hard-codes the food label '${label}'`).not.toMatch(new RegExp(`['"\`]${label}['"\`]`));
      }
      // name matching would need these
      expect(source, file).not.toMatch(/normalize\(['"]NF[DC]['"]\)|stripDiacritics|toLowerCase\(\)\.includes/);
    }
  });

  it('matching reads foodKeys only, never a restaurant\'s name', () => {
    const source = read(join(SRC, 'utils/mapFood.js'));
    expect(source).toMatch(/foodKeys/);
    expect(source).not.toMatch(/\.name\b/);
  });

  it('no popularity, activity or realtime claims', () => {
    for (const file of MAP_FILES) {
      const source = code(file).replace(/aria-live/g, '');
      for (const banned of BANNED) expect(source, `${file} ${banned}`).not.toMatch(banned);
    }
  });

  it('the URL carries q or food only — never coordinates, bounds or zoom', () => {
    for (const file of MAP_FILES) {
      const source = read(file);
      expect(source, file).not.toMatch(/[?&](lat|lng|zoom|center|bounds|swLat|swLng|neLat|neLng)=/);
      for (const [, args] of source.matchAll(/setSearchParams\(\s*(\{[^}]*\})/g)) {
        const keys = [...args.matchAll(/(\w+)\s*:/g)].map((m) => m[1]);
        expect(keys.every((k) => k === 'q' || k === 'food'), `${file}: setSearchParams(${args})`).toBe(true);
      }
      expect(source, file).not.toMatch(/directionsUrl|google\.com\/maps/);
    }
  });

  it('the viewport is never persisted: no browser storage in map modules', () => {
    for (const file of MAP_FILES) {
      expect(code(file), file).not.toMatch(/localStorage|sessionStorage|indexedDB|document\.cookie/);
    }
  });

  it('no Goong Places or ingestion paths, and no synthetic product data (P2 included)', () => {
    for (const file of MAP_FILES) {
      const source = code(file);
      expect(source, file).not.toMatch(/rsapi\.goong|Place\/AutoComplete|Place\/Detail|\/places\/|getNearby|\/nearby|ingest/i);
      expect(source, file).not.toMatch(/faker|synthetic|demoData|fakeRating|bookmarkCount|popularity/i);
    }
  });

  it('no inferred truncation: the 200-row viewport cap is never compared against', () => {
    for (const file of MAP_FILES) {
      expect(read(file), file).not.toMatch(/[<>]=?\s*(199|200|201)\b|\b(199|200|201)\s*[<>=]|200\+/);
    }
  });

  it('no activity animation on pins: no pulse, bounce or ping on .restaurant-pin', () => {
    const css = read(join(SRC, 'index.css'));
    const pinRules = [...css.matchAll(/\.restaurant-pin[^{]*\{[^}]*\}/g)].map((m) => m[0]).join('\n');
    expect(pinRules.length).toBeGreaterThan(200);
    expect(pinRules).not.toMatch(/animate-|animation|@keyframes/);
    // Goong owns the marker root's transform
    expect(pinRules).not.toMatch(/(^|[^-])transform\s*:|translate-|scale-|rotate-/);
  });
});
