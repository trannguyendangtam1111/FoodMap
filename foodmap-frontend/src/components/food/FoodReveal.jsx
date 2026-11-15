import { forwardRef } from 'react';
import FoodArt from '../art/FoodArt';
import { countText, radiusText } from './FoodChip';
import { inSentence, scopeDtoLabel } from '../../utils/scope';
import { t, useI18n } from '../../i18n';
import { foodName } from '../../i18n/foodNames';

/**
 * FoodReveal — the FOOD that came out of the cà mèn.
 *
 * Shows only what the food decision contains: the food's art, its label as
 * the headline (the server's label — there is no local food list) and the
 * supply line "N quán [trong X km] {scope}". No restaurant, rating, distance
 * or price: the answer here is a food, not a place. Counts are what FoodMap
 * knows (by name and menu), never popularity.
 *
 * The art is decorative; the heading carries the name. The forwarded ref
 * lands on the heading, for focus after the first reveal.
 * `animate={false}` shows it without the reveal (a restored, not new, answer).
 */

/** "3 quán quanh trung tâm TP.HCM", "2 quán trong 5 km gần bạn". */
export function supplyLine(decision) {
  const { restaurantCount, restaurantCountCapped, radiusMeters, radiusWidened, scope } = decision;
  return [
    countText(restaurantCount, restaurantCountCapped),
    radiusWidened && Number.isFinite(radiusMeters) ? t('food.within', { radius: radiusText(radiusMeters) }) : null,
    inSentence(scopeDtoLabel(scope)),
  ].filter(Boolean).join(' ');
}

const FoodReveal = forwardRef(function FoodReveal({ decision, inert = false, animate = true, className = '' }, headingRef) {
  useI18n();
  const { food } = decision;
  return (
    <article
      aria-labelledby="food-result-name"
      data-food-key={food.key}
      // React 18 has no `inert` prop; the attribute works when present.
      inert={inert ? '' : undefined}
      className={`${animate ? 'fm-reveal' : ''} flex items-center gap-4 rounded-tile border-2 border-ink bg-surface p-4
                  text-left shadow-e2 sm:gap-5 sm:p-5 ${className}`}
    >
      <FoodArt
        artKey={food.artKey ?? food.key}
        parentCategory={food.parentCategory}
        tone={food.tone}
        className="h-20 w-20 flex-shrink-0 rounded-full border border-line sm:h-28 sm:w-28"
      />
      <div className="min-w-0">
        <h3
          id="food-result-name"
          ref={headingRef}
          tabIndex={-1}
          className="break-words font-display text-h1 text-ink"
        >
          {foodName(food)}
        </h3>
        <p className="mt-1 text-small text-ink-muted">{supplyLine(decision)}</p>
      </div>
    </article>
  );
});

export default FoodReveal;
