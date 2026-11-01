import FoodGlyph from '../art/FoodGlyph';
import { FOOD_ART_KEYS, getFoodArt } from '../art/foodArtRegistry';
import { TONE_COLOR } from '../art/primitives';

/**
 * Every food drawing once, as SVG <symbol>s, so map pins and food pills can
 * show a glyph with a <use> reference instead of a copy of the drawing each
 * (up to 200 pins). The symbols render FoodGlyph itself — nothing is redrawn.
 * currentColor (the food's tone) is set on the referencing <svg>.
 *
 * Render it once on the page that uses glyphHref.
 */
const COVERED_DISH = 'covered-dish';

/** The fragment id of a food's glyph; an unknown art key → the neutral covered dish. */
export function glyphHref(artKey) {
  return `#fm-glyph-${getFoodArt(artKey) ? artKey : COVERED_DISH}`;
}

/** The colour of a catalog tone, or null (CSS then decides). */
export function toneColor(tone) {
  return TONE_COLOR[tone] ?? null;
}

export default function FoodGlyphSprite() {
  return (
    <svg aria-hidden="true" focusable="false" width="0" height="0" className="absolute h-0 w-0 overflow-hidden">
      {[...FOOD_ART_KEYS, COVERED_DISH].map((key) => (
        <symbol key={key} id={`fm-glyph-${key}`} viewBox="0 0 64 64">
          <FoodGlyph artKey={key === COVERED_DISH ? null : key} />
        </symbol>
      ))}
    </svg>
  );
}

/** A small glyph from the sprite (decorative; the food's name sits next to it). */
export function GlyphIcon({ food, className = 'h-4 w-4' }) {
  const color = toneColor(food?.tone);
  return (
    <svg
      viewBox="0 0 64 64"
      aria-hidden="true"
      focusable="false"
      className={`flex-shrink-0 ${className}`}
      style={color ? { color } : undefined}
    >
      <use href={glyphHref(food?.artKey ?? food?.key)} />
    </svg>
  );
}
