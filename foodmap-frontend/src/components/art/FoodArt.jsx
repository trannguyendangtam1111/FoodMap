import CategoryArt from '../restaurant/CategoryArt';
import { CATEGORIES } from '../../utils/category';
import { getFoodArt } from './foodArtRegistry';
import { STROKE } from './primitives';

/**
 * FoodArt — the illustration of a FOOD (food chips, later the food page and
 * the cà mèn reveal). Restaurants keep CategoryArt.
 *
 * Same frame as CategoryArt so the two can sit side by side: a tinted
 * background in the food's tone, the drawing at 64 % of the box, the caller
 * choosing size and radius through className. Decorative (aria-hidden): the
 * food's name is always rendered next to it.
 *
 * Fallback, never empty: the drawing for `artKey` → CategoryArt for
 * `parentCategory` → CategoryArt `other`. A fallback never pretends to be a
 * specific food.
 */

// Literal class strings so Tailwind can see them (same set as CategoryArt).
const TONES = {
  chili:       'bg-chili-tint text-chili',
  turmeric:    'bg-turmeric-tint text-turmeric',
  crust:       'bg-crust-tint text-crust',
  taro:        'bg-taro-tint text-taro',
  pandan:      'bg-pandan-tint text-pandan',
  dragonfruit: 'bg-dragonfruit-tint text-dragonfruit',
  brand:       'bg-brand-50 text-brand-400',
};

export default function FoodArt({ artKey, parentCategory, tone, className = '' }) {
  const Drawing = getFoodArt(artKey);
  const category = CATEGORIES[parentCategory] ?? CATEGORIES.other;
  const toneKey = TONES[tone] ? tone : category.tone;

  if (!Drawing) {
    return <CategoryArt category={{ ...category, tone: toneKey }} className={className} />;
  }

  return (
    <div
      aria-hidden="true"
      data-food-art={artKey}
      className={`relative flex items-center justify-center overflow-hidden ${TONES[toneKey] ?? TONES.brand} ${className}`}
    >
      <svg viewBox="0 0 64 64" {...STROKE} className="relative h-[64%] max-h-44 w-auto" focusable="false">
        <Drawing />
      </svg>
    </div>
  );
}
