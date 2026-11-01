import { CATEGORIES } from '../../utils/category';

/**
 * CategoryArt — flat illustration standing in for a restaurant photo.
 *
 * Every drawing shares one 64×64 grid, a 2.5px ink outline, the category's
 * accent as the main fill (`currentColor`) and cream highlights. No faces on
 * food. It fills its container, so the caller decides the size and radius.
 * Decorative: the restaurant name is always shown next to it.
 */

// Literal class strings so Tailwind can see them.
const TONES = {
  chili:       'bg-chili-tint text-chili',
  turmeric:    'bg-turmeric-tint text-turmeric',
  crust:       'bg-crust-tint text-crust',
  taro:        'bg-taro-tint text-taro',
  pandan:      'bg-pandan-tint text-pandan',
  dragonfruit: 'bg-dragonfruit-tint text-dragonfruit',
  brand:       'bg-brand-50 text-brand-400',
};

const CREAM = 'rgb(var(--fm-cream))';
const INK = 'rgb(var(--fm-ink))';

const Steam = ({ xs = [24, 32, 40], y = 20 }) => (
  <g strokeWidth={2}>
    {xs.map((x) => <path key={x} d={`M${x} ${y}c-2-3 2-5 0-8`} />)}
  </g>
);

const Bowl = () => (
  <>
    <path d="M26 52h12l-1.5 4h-9z" fill="currentColor" />
    <path d="M9 31h46c0 13-10 22-23 22S9 44 9 31z" fill="currentColor" />
    <path d="M15 37c1.5 5 4.5 8.5 9 10.5" stroke={CREAM} strokeWidth={2.5} />
  </>
);

const ART = {
  noodle: () => (
    <>
      <path d="M41 5 29 29M47 8 33 30" strokeWidth={2.5} />
      <path d="M13 31c3-6 9-8 19-8s16 2 19 8z" fill={CREAM} />
      <path d="M19 27.5c2-2 4 2 6 0s4 2 6 0 4 2 6 0" strokeWidth={2} />
      <Bowl />
      <Steam xs={[19, 25]} y={19} />
    </>
  ),
  rice: () => (
    <>
      <path d="M14 31c0-9 8-14 18-14s18 5 18 14z" fill={CREAM} />
      <path d="M24 24l2 1M31 21l2-1M38 25l2 1M28 28h2" strokeWidth={2} />
      <Bowl />
      <Steam xs={[26, 38]} y={13} />
    </>
  ),
  vietnamese: () => (
    // Nón lá (conical hat)
    <>
      <path d="M32 12 58 40c-17 6-35 6-52 0z" fill="currentColor" />
      <path d="M14 33c12 3 24 3 36 0M21 25c7 2 15 2 22 0" strokeWidth={1.75} />
      <path d="M30 17 17 33" stroke={CREAM} strokeWidth={2.5} />
      <path d="M21 43c4 9 18 9 22 0" strokeWidth={2} />
    </>
  ),
  hotpot: () => (
    <>
      <path d="M12 31h40v11a10 10 0 0 1-10 10H22a10 10 0 0 1-10-10z" fill="currentColor" />
      <path d="M12 35H7v6h5M52 35h5v6h-5" />
      <path d="M9 27h46v5H9z" fill={CREAM} />
      <path d="M17 38c1 4 3 7 6 8" stroke={CREAM} strokeWidth={2.5} />
      <Steam xs={[24, 32, 40]} y={21} />
    </>
  ),
  seafood: () => (
    // Fish — deliberately no eye
    <>
      <path d="M48 32l10-9v18z" fill="currentColor" />
      <path d="M8 32c8-12 28-14 40 0-12 14-32 12-40 0z" fill="currentColor" />
      <path d="M27 21.5l4-6 7 5" fill={CREAM} />
      <path d="M20 25c3 4 3 10 0 14" strokeWidth={2} />
      <path d="M26 29c5-3 10-3 15 0" stroke={CREAM} strokeWidth={2.5} />
    </>
  ),
  japanese: () => (
    // Nigiri
    <>
      <path d="M12 39c0-5 4-8 8-8h24c4 0 8 3 8 8v4a4 4 0 0 1-4 4H16a4 4 0 0 1-4-4z" fill={CREAM} />
      <path d="M10 33c4-8 12-10 22-10s18 2 22 10c-6 2-14 3-22 3s-16-1-22-3z" fill="currentColor" />
      <path d="M21 26l4 7M31 24l3 9M41 26l2 7" stroke={CREAM} strokeWidth={2} />
      <path d="M28 30.5h8V47h-8z" fill={INK} />
    </>
  ),
  pizza: () => (
    <>
      <path d="M32 56 12 19c13-5 27-5 40 0z" fill={CREAM} />
      <path d="M10 15.5c14-7 30-7 44 0l-2.5 5c-12.5-5-26.5-5-39 0z" fill="currentColor" />
      <circle cx="26" cy="28" r="4" fill="rgb(var(--fm-chili))" strokeWidth={2} />
      <circle cx="37.5" cy="31" r="3.5" fill="rgb(var(--fm-chili))" strokeWidth={2} />
      <circle cx="31" cy="41" r="3" fill="rgb(var(--fm-chili))" strokeWidth={2} />
    </>
  ),
  bakery: () => (
    // Bánh mì
    <>
      <path d="M8 39c0-8 10-16 26-18s24 4 22 10-12 14-28 16S8 47 8 39z" fill="currentColor" />
      <path d="M20 31l4 6M30 27l4 6M40 25l4 6" stroke={CREAM} strokeWidth={2.5} />
      <path d="M13 42c4 2 10 2 16 1" stroke={CREAM} strokeWidth={2} />
    </>
  ),
  coffee: () => (
    // Takeaway cup — cà phê / trà sữa
    <>
      <path d="M36 15l6-10" strokeWidth={3} />
      <path d="M18 23h28l-3 31a4 4 0 0 1-4 3.5H25a4 4 0 0 1-4-3.5z" fill={CREAM} />
      <path d="M19.5 33h25l-1.3 12H20.8z" fill="currentColor" />
      <path d="M17 23a2 2 0 0 1-2-2v-1c0-2 2-4 4-4h26c2 0 4 2 4 4v1a2 2 0 0 1-2 2z" fill="currentColor" />
    </>
  ),
  vegetarian: () => (
    <>
      <path d="M12 51C10 29 24 13 52 13c0 26-14 40-40 38z" fill="currentColor" />
      <path d="M14 49 42 23" strokeWidth={2.25} />
      <path d="M24 39l-2-8M30 33l8 1M35 28l-1-7" strokeWidth={1.75} />
      <path d="M45 18c-8 1-15 4-20 9" stroke={CREAM} strokeWidth={2.5} />
    </>
  ),
  dessert: () => (
    // Ice-cream cone
    <>
      <path d="M21 31l11 27 11-27z" fill={CREAM} />
      <path d="M26 35l9 9M31 33l7 7M37 34l-9 13" strokeWidth={1.5} />
      <path d="M18 30a14 13 0 1 1 28 0c0 2-2 3-4 3H22c-2 0-4-1-4-3z" fill="currentColor" />
      <path d="M24 22c1-4 4-6 8-7" stroke={CREAM} strokeWidth={2.5} />
    </>
  ),
  other: () => (
    // Plate with fork and knife — generic "quán ăn"
    <>
      <circle cx="32" cy="34" r="17" fill={CREAM} />
      <circle cx="32" cy="34" r="10" fill="currentColor" strokeWidth={2} />
      <path d="M7 16v11a3 3 0 0 0 6 0V16M10 16v40" />
      <path d="M57 56V16c-3 3-5 8-5 14v8h5" />
    </>
  ),
};

export default function CategoryArt({ category = CATEGORIES.other, pattern = false, className = '' }) {
  const Art = ART[category.key] ?? ART.other;
  return (
    <div
      aria-hidden="true"
      className={`relative flex items-center justify-center overflow-hidden ${TONES[category.tone] ?? TONES.brand} ${className}`}
    >
      {pattern && (
        <div className="absolute inset-0 opacity-25 bg-[radial-gradient(currentColor_1.5px,transparent_1.5px)] bg-[length:18px_18px]" />
      )}
      <svg
        viewBox="0 0 64 64"
        fill="none"
        stroke={INK}
        strokeWidth={2.5}
        strokeLinecap="round"
        strokeLinejoin="round"
        className="relative h-[64%] max-h-44 w-auto"
      >
        <Art />
      </svg>
    </div>
  );
}
