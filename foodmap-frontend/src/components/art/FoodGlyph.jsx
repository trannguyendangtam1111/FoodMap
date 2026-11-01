import CoveredDishArt from './foods/CoveredDishArt';
import { getFoodArt } from './foodArtRegistry';
import { STROKE, TONE_COLOR } from './primitives';

/**
 * FoodGlyph — a food's drawing as a bare SVG <g>, to place inside another
 * SVG (the cà mèn can lift it out of the box in a later part). It renders
 * the very same drawing component as FoodArt — nothing is redrawn.
 *
 *   x, y   top-left corner in the host SVG's units
 *   size   rendered size in those units (the drawing is 64 units square)
 *   tone   catalog tone for currentColor; otherwise inherited from the host
 *
 * Unknown key → a neutral covered dish (never a specific food). Decorative.
 */
export default function FoodGlyph({ artKey, x = 0, y = 0, size = 64, tone }) {
  const Drawing = getFoodArt(artKey) ?? CoveredDishArt;
  const color = TONE_COLOR[tone];
  return (
    <g
      aria-hidden="true"
      data-food-glyph={getFoodArt(artKey) ? artKey : 'covered-dish'}
      transform={`translate(${x} ${y}) scale(${size / 64})`}
      style={color ? { color } : undefined}
      {...STROKE}
    >
      <Drawing />
    </g>
  );
}
