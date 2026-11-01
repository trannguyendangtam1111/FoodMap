import { readdirSync, readFileSync, statSync } from 'node:fs';
import { join, resolve } from 'node:path';
import { describe, expect, it } from 'vitest';

// Static guards for the food-first flow (Master Specification AC-D6, D2):
// the server picks foods and owns their labels, so the app source (tests and
// fixtures excluded) must neither generate randomness nor carry its own copy
// of the catalog's food labels.

const SRC = resolve(process.cwd(), 'src');

function sourceFiles(dir = SRC) {
  return readdirSync(dir).flatMap((name) => {
    const path = join(dir, name);
    if (statSync(path).isDirectory()) return name === 'test' ? [] : sourceFiles(path);
    return /\.(jsx?|tsx?)$/.test(name) && !/\.test\.(jsx?|tsx?)$/.test(name) ? [path] : [];
  });
}

function catalogLabels() {
  const yml = readFileSync(resolve(process.cwd(), '../foodmap-backend/src/main/resources/catalog/food-catalog.yml'), 'utf8');
  const labels = [];
  let label = null;
  for (const line of yml.split(/\r?\n/)) {
    const m = line.match(/^\s*label:\s*(.+?)\s*$/);
    if (m) label = m[1];
    if (label && /^\s*discoverable:\s*true\b/.test(line)) { labels.push(label); label = null; }
  }
  return labels;
}

describe('food-first source guards', () => {
  const files = sourceFiles();

  it('no app module generates randomness — the server decides', () => {
    expect(files.length).toBeGreaterThan(20);
    for (const file of files) {
      expect(readFileSync(file, 'utf8'), file).not.toMatch(/Math\.random|getRandomValues|randomUUID/);
    }
  });

  it('no app module hard-codes a catalog food label — labels come from the server', () => {
    const labels = catalogLabels();
    expect(labels).toHaveLength(20);
    // One exception: the restaurant-CATEGORY presentation table (utils/category.js)
    // labels the server's `seafood` category "Hải sản", which is also a food's
    // label. It is presentation for a server-sent category key, not food
    // vocabulary (its keyword inference was retired in Part 8, AC-X4).
    const exempt = (file) => file.replace(/\\/g, '/').endsWith('/src/utils/category.js');
    expect(files.some(exempt)).toBe(true);
    for (const file of files.filter((f) => !exempt(f))) {
      const source = readFileSync(file, 'utf8');
      for (const label of labels) {
        expect(source, `${file} contains '${label}'`).not.toMatch(new RegExp(`['"\`]${label}['"\`]`));
      }
    }
  });
});

// ── Part 8: restaurants are classified by the server only ─────────────────────

const CATEGORY_KEYS = ['noodle', 'hotpot', 'rice', 'vietnamese', 'bakery', 'pizza', 'coffee', 'vegetarian',
  'dessert', 'japanese', 'seafood', 'other'];

/** Every keyword and suppressor of the server's catalog (they never leave the server). */
function catalogMatchWords() {
  const yml = readFileSync(resolve(process.cwd(), '../foodmap-backend/src/main/resources/catalog/food-catalog.yml'), 'utf8');
  const keys = new Set([...yml.matchAll(/^\s*- key:\s*(\S+)/gm)].map((m) => m[1]));
  const words = new Set();
  for (const [, , body] of yml.matchAll(/(keywords|suppressors):\s*\[([\s\S]*?)\]\s*$/gm)) {
    for (const [, text] of body.matchAll(/text:\s*([^,}]+)/g)) words.add(text.trim().toLowerCase());
    body.replace(/\{[^}]*\}/g, '').split(',').map((w) => w.trim().toLowerCase()).filter(Boolean).forEach((w) => words.add(w));
  }
  // food keys and category keys are identifiers the API sends, not vocabulary
  return [...words].filter((w) => !keys.has(w) && !CATEGORY_KEYS.includes(w));
}

describe('server-owned classification (Part 8)', () => {
  const files = sourceFiles();

  it('the old frontend inference is gone: no inferCategory, no keyword tables', () => {
    for (const file of files) {
      expect(readFileSync(file, 'utf8'), file).not.toMatch(/inferCategory|KEYWORDS|RESTAURANT_WORDS|matchMenu|stripDiacritics/);
    }
  });

  it('no app module carries a list of catalog keywords (matching happens on the server)', () => {
    const words = catalogMatchWords();
    expect(words.length).toBeGreaterThan(40);
    for (const file of files) {
      const source = readFileSync(file, 'utf8').toLowerCase();
      const quoted = words.filter((w) => source.includes(`'${w}'`) || source.includes(`"${w}"`));
      // a stray word (a type tag, a category label) is not a vocabulary; a list would be many
      expect(quoted.length, `${file}: ${quoted.join(', ')}`).toBeLessThan(3);
    }
  });

  it('utils/category.js holds presentation only, keyed by the server\'s category', () => {
    const source = readFileSync(resolve(SRC, 'utils/category.js'), 'utf8');
    expect([...source.matchAll(/^export (?:const|function) (\w+)/gm)].map((m) => m[1]))
      .toEqual(['CATEGORIES', 'categoryOf', 'isKnownCategory']);
    expect(source).toMatch(/restaurant\?\.category/);
  });
});

// ── Count semantics: the server says whether a count is exact ────────────────

describe('count semantics', () => {
  it('no app module infers "at least" from the number (the server\'s cap is never hard-coded)', () => {
    // the modules that fetch or show food and restaurant counts (a review's 2000-character limit is not one)
    const countModules = sourceFiles().filter((f) => /(components[\\/](food|restaurant|decision|home)[\\/]|pages[\\/](FoodPage|HomePage)\.jsx$|api[\\/](decision|catalog)\.api\.js$|hooks[\\/]use(Food|Available|Tiffin))/.test(f));
    expect(countModules.length).toBeGreaterThan(15);
    for (const file of countModules) {
      // a comparison with the cap, a hard-coded "2000+", or the server's constant
      expect(readFileSync(file, 'utf8'), file)
        .not.toMatch(/[<>]=?\s*(1999|2000|2001)\b|\b(1999|2000|2001)\s*[<>]|2000\+|SAFETY_CAP/);
    }
  });
});
