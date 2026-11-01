import { forwardRef } from 'react';
import { Link } from 'react-router-dom';
import { buttonClass } from '../ui/Button';
import Chip from '../ui/Chip';
import Icon from '../ui/Icon';
import RestaurantThumb from '../restaurant/RestaurantThumb';
import RestaurantMeta from '../restaurant/RestaurantMeta';
import DirectionsLink from '../restaurant/DirectionsLink';
import { categoryOf, isKnownCategory } from '../../utils/category';
import { formatDistance } from '../../utils/ranking';

/**
 * DecisionReveal — the restaurant that came out of the cà mèn.
 *
 * Takes a DecisionResponse (backend shape) and shows only what it contains:
 * photo or CategoryArt, the name as the headline, address, the facts line
 * (RestaurantMeta — every item only when its data exists) and the backend's
 * own reason labels. Nothing is padded: a distance only when the backend
 * measured it from the user's real location (locationSource USER), no rating
 * without reviews, no opening status (the summary has no hours).
 *
 * Fallbacks are explained in a neutral note, never as an error and never as
 * "near you" when the pool was the city centre (see decisionContext).
 *
 * A reason the facts line already shows is not repeated as a chip:
 * RATING when the restaurant is rated, DISTANCE when a distance is known.
 *
 * The forwarded ref lands on the name heading, for focus after the reveal.
 * `animate={false}` shows it without the reveal (a restored, not new, answer).
 * `showContext={false}` leaves the fallback note to the caller (the food page
 * states its scope and any widened radius once, above the results).
 * `directions` adds "Chỉ đường" beside "Xem quán" (only with valid coordinates).
 * `linkState` is passed to "Xem quán" as router state (e.g. the food page's food).
 */
// Reason codes from the backend's DecisionReason.
const REASON_ICONS = {
  AREA: 'map-pin',
  DISTANCE: 'map-pin',
  MOOD_NAME: 'utensils',
  MOOD_MENU: 'utensils',
  FOOD_NAME: 'utensils',
  FOOD_MENU: 'utensils',
};

/**
 * One honest sentence about how this answer was found, or null when there
 * is nothing worth saying (fallbackLevel 0 with the user's location).
 *   CITY_CENTER (level 3) → no location was used; the AREA chip names the area
 *   level 2               → the radius was widened to radiusMeters
 *   moodMatched false     → the requested mood was relaxed (level 1)
 */
export function decisionContext({ locationSource, fallbackLevel, radiusMeters, moodMatched } = {}) {
  if (locationSource === 'CITY_CENTER') return 'Bạn chưa chia sẻ vị trí, nên FoodMap gợi ý theo khu vực.';
  if (fallbackLevel === 2 && radiusMeters > 0) {
    return `Gần bạn ít quán, nên FoodMap đã tìm rộng hơn, trong khoảng ${formatDistance(radiusMeters / 1000)}.`;
  }
  if (moodMatched === false) return 'Chưa có quán đúng món bạn chọn, đây là một quán khác gần bạn.';
  return null;
}

const DecisionReveal = forwardRef(function DecisionReveal({
  decision, inert = false, animate = true, showContext = true, directions = false, linkState, className = '',
}, headingRef) {
  const { restaurant, distanceMeters = null, reasons = [], locationSource } = decision;
  const category = categoryOf(restaurant);
  // A distance is a claim about the user — only for the user's own location.
  const distanceKm = locationSource === 'USER' && distanceMeters != null ? distanceMeters / 1000 : null;
  const context = showContext ? decisionContext(decision) : null;

  const shownByMeta = {
    RATING: Number(restaurant.avgRating) > 0,
    DISTANCE: distanceKm != null,
  };
  const chips = reasons.filter((r) => r?.label && !shownByMeta[r.code]);

  return (
    <article
      aria-labelledby="decision-result-name"
      // React 18 has no `inert` prop; the attribute works when present.
      inert={inert ? '' : undefined}
      className={`${animate ? 'fm-reveal' : ''} overflow-hidden rounded-tile border-2 border-ink bg-surface text-left shadow-e2
                  md:flex lg:block ${className}`}
    >
      <div className="relative md:w-56 md:flex-shrink-0 lg:w-auto">
        <RestaurantThumb
          restaurant={restaurant}
          category={category}
          className="h-24 w-full border-b-2 border-ink sm:h-36 md:h-full md:min-h-[12rem] md:border-b-0 md:border-r-2
                     lg:h-36 lg:min-h-0 lg:border-b-2 lg:border-r-0"
        />
        {isKnownCategory(category) && (
          <Chip tone={category.tone} className="absolute left-3 top-3 shadow-e1">
            {category.label}
          </Chip>
        )}
      </div>

      <div className="flex min-w-0 flex-1 flex-col p-4 sm:p-5">
        <h3
          id="decision-result-name"
          ref={headingRef}
          tabIndex={-1}
          className="break-words font-display text-h2 text-ink"
        >
          {restaurant.name}
        </h3>

        {restaurant.address && (
          <p className="mt-1.5 flex items-start gap-1.5 text-small text-ink-muted">
            <Icon name="map-pin" size={16} className="mt-0.5" />
            <span className="line-clamp-2 break-words">{restaurant.address}</span>
          </p>
        )}

        <RestaurantMeta restaurant={restaurant} distanceKm={distanceKm} showUnrated={false} className="mt-2" />

        {chips.length > 0 && (
          <ul aria-label="Lý do gợi ý" className="mt-3 flex flex-wrap gap-1.5">
            {chips.map((r) => (
              <li key={`${r.code}-${r.label}`} className="min-w-0 max-w-full">
                <Chip
                  wrap
                  tone="brand"
                  icon={REASON_ICONS[r.code] && <Icon name={REASON_ICONS[r.code]} size={12} strokeWidth={2} />}
                >
                  {r.label}
                </Chip>
              </li>
            ))}
          </ul>
        )}

        {context && (
          <p className="mt-3 flex items-start gap-1.5 text-small text-ink-muted">
            <Icon name="globe" size={16} className="mt-0.5" />
            <span>{context}</span>
          </p>
        )}

        <div className="mt-4 flex flex-wrap gap-2">
          <Link
            to={`/restaurants/${restaurant.id}`}
            state={linkState}
            className={buttonClass({ variant: 'primary', size: 'md' })}
          >
            Xem quán
            <span className="sr-only"> {restaurant.name}</span>
            <Icon name="chevron-left" size={18} strokeWidth={2.25} className="rotate-180" />
          </Link>
          {directions && <DirectionsLink restaurant={restaurant} />}
        </div>
      </div>
    </article>
  );
});

export default DecisionReveal;
