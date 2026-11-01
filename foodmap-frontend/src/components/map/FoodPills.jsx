import { pillFoods } from '../../utils/mapFood';
import { GlyphIcon } from './FoodGlyphSprite';

/**
 * The foods the server matched for a restaurant (its `foodKeys`), as small
 * pills: glyph + catalog label. Discoverable foods only, server order, the
 * selected food first, at most three plus "+N". Nothing when there is none —
 * no "không rõ" filler. Needs FoodGlyphSprite on the page.
 *
 *   foods        the catalog's foods as a Map by key (null while loading)
 *   selectedKey  the food chosen on the map, if any
 */
export default function FoodPills({ foodKeys, foods, selectedKey = null, className = '' }) {
  const { shown, more } = pillFoods(foodKeys, foods, selectedKey);
  if (shown.length === 0) return null;

  return (
    <ul aria-label="Món có ở quán" className={`flex flex-wrap gap-1 ${className}`}>
      {shown.map((food) => (
        <li
          key={food.key}
          data-food-pill={food.key}
          className={`inline-flex items-center gap-1 rounded-full border py-0.5 pl-1 pr-2 text-label font-medium text-ink
                      ${food.key === selectedKey ? 'border-brand-400 bg-brand-50' : 'border-line bg-surface-sunken'}`}
        >
          <GlyphIcon food={food} />
          {food.label}
        </li>
      ))}
      {more > 0 && (
        <li
          aria-label={`và ${more} món khác`}
          className="inline-flex items-center rounded-full bg-surface-sunken px-2 py-0.5 text-label font-medium text-ink-muted"
        >
          +{more}
        </li>
      )}
    </ul>
  );
}
