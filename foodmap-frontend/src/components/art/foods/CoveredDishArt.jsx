import { CREAM, CYAN } from '../primitives';

/**
 * Neutral last-resort drawing (a covered dish) for FoodGlyph when a key has
 * no drawing: it suggests "a dish" without claiming any particular food.
 * Accent: plate.
 */
export default function CoveredDishArt() {
  return (
    <>
      <rect x="8" y="44" width="48" height="5" rx="2.5" fill={CYAN} />
      <path d="M12 44a20 18 0 0 1 40 0z" fill={CREAM} />
      <circle cx="32" cy="23.5" r="3" fill={CREAM} />
      <path d="M19 38c1-5 4-8 8-9.5" strokeWidth={2} />
    </>
  );
}
