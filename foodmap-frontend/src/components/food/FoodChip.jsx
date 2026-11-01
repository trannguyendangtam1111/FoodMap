import { Link } from 'react-router-dom';
import FoodArt from '../art/FoodArt';

/**
 * One available food in "Thèm gì?": art, the server's label and the real
 * number of restaurants FoodMap knows with it in the current scope.
 *
 * Art: FoodArt for the food (falls back to its parent category's
 * CategoryArt). The available-foods payload carries no artKey; the catalog's
 * artKey defaults to the food key, so the key is used.
 *
 * Links to /mon/{key} with the scope carried as ?area=… or ?near=1 — never
 * coordinates.
 */

const KIND_TAGS = { DRINK: 'Đồ uống', DESSERT: 'Tráng miệng' };

const VI_KM = new Intl.NumberFormat('vi-VN', { maximumFractionDigits: 1 });

/**
 * "6 quán"; "6+ quán" when the server marks the count as a lower bound
 * (`atLeast` — its countCapped flag, never guessed from the number); shown
 * at most as "99+ quán" (the chip's display limit, Master Specification §9).
 */
export function countText(count, atLeast = false) {
  if (count >= 100) return '99+ quán';
  return atLeast ? `${count}+ quán` : `${count} quán`;
}

/** "5 km", "2,4 km". */
export function radiusText(meters) {
  return `${VI_KM.format(meters / 1000)} km`;
}

export default function FoodChip({ food, href, scopePhrase }) {
  const widened = food.radiusWidened && Number.isFinite(food.radiusMeters);
  const kindTag = KIND_TAGS[food.kind];
  const accessibleName = [
    `${food.label}, ${countText(food.count, food.countCapped)}`,
    widened ? `trong ${radiusText(food.radiusMeters)}` : null,
    scopePhrase,
  ].filter(Boolean).join(' ');

  return (
    <li className="flex-shrink-0 snap-start">
      <Link
        to={href}
        aria-label={accessibleName}
        className="group flex min-h-11 w-[6.25rem] flex-col items-center gap-1.5 rounded-tile px-1 py-1.5 text-center sm:w-auto"
      >
        <FoodArt
          artKey={food.artKey ?? food.key}
          parentCategory={food.parentCategory}
          tone={food.tone}
          className="h-20 w-20 rounded-full border border-line
                     transition-[transform,box-shadow] duration-fast ease-standard
                     group-hover:-translate-y-0.5 group-hover:shadow-e2 lg:h-24 lg:w-24"
        />
        <span className="text-small font-semibold text-ink transition-colors group-hover:text-brand-700">
          {food.label}
        </span>
        <span className="text-label font-medium text-ink-muted">
          {countText(food.count, food.countCapped)}
          {widened && (
            <>
              {/* narrow chips: the radius on its own line, never split ("5 / km") */}
              <span className="hidden lg:inline"> · </span>
              <span className="block whitespace-nowrap lg:inline">trong {radiusText(food.radiusMeters)}</span>
            </>
          )}
        </span>
        {kindTag && (
          <span className="rounded-full bg-surface-sunken px-2 py-0.5 text-label font-medium text-ink-muted">
            {kindTag}
          </span>
        )}
      </Link>
    </li>
  );
}
