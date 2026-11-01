/**
 * Shared vocabulary for FoodArt drawings (Master Specification §10.2).
 *
 *   grid      64×64 viewBox; stroke 2.5, round caps and joins (set by the
 *             parent <svg>/<g>, so drawings only add fills)
 *   outline   --fm-art-outline (defaults to ink)
 *   main      currentColor — the food's tone, set by the wrapper
 *   highlight cream
 *   details   existing tones only (chili meat, pandan herbs, turmeric egg,
 *             crust bread, …)
 *   accent    exactly one cyan element per drawing
 *
 * No faces, no text, no gradients, no filters, ≤ ~12 shapes, features ≥ 3 units.
 */

export const OUTLINE = 'rgb(var(--fm-art-outline))';
export const CREAM = 'rgb(var(--fm-cream))';
/** The one cyan brand accent per drawing. */
export const CYAN = 'rgb(var(--fm-brand-400))';

export const TONE = Object.freeze({
  chili: 'rgb(var(--fm-chili))',
  chiliInk: 'rgb(var(--fm-chili-ink))',
  turmeric: 'rgb(var(--fm-turmeric))',
  crust: 'rgb(var(--fm-crust))',
  taro: 'rgb(var(--fm-taro))',
  pandan: 'rgb(var(--fm-pandan))',
  dragonfruit: 'rgb(var(--fm-dragonfruit))',
});

/** The colour a `tone` key sets as currentColor (same keys as the catalog's tones). */
export const TONE_COLOR = Object.freeze({
  chili: TONE.chili,
  turmeric: TONE.turmeric,
  crust: TONE.crust,
  taro: TONE.taro,
  pandan: TONE.pandan,
  dragonfruit: TONE.dragonfruit,
  brand: CYAN,
});

/** Common stroke settings for any element that renders a drawing. */
export const STROKE = Object.freeze({
  stroke: OUTLINE,
  strokeWidth: 2.5,
  strokeLinecap: 'round',
  strokeLinejoin: 'round',
  fill: 'none',
});

/** The small foot under a bowl, in the bowl's fill. */
export const BowlFoot = ({ y, fill = 'currentColor' }) => (
  <path d={`M26 ${y}h12l-1.4 4h-9.2z`} fill={fill} />
);
