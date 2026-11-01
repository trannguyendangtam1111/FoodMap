import { Link } from 'react-router-dom';
import Icon from '../ui/Icon';
import { buttonClass } from '../ui/Button';
import RestaurantThumb from '../restaurant/RestaurantThumb';
import RestaurantMeta from '../restaurant/RestaurantMeta';
import { categoryOf, isKnownCategory } from '../../utils/category';
import { matchesFood } from '../../utils/mapFood';
import FoodPills from './FoodPills';

/*
 * Content for Goong map popups. MapView renders these into the popup's DOM
 * node with their own React root, outside the router — so navigation is a
 * callback, not a <Link>.
 *
 * Both cards show only facts that exist: no empty ratings, prices or
 * categories, and distance only when measured from the real user location.
 */

/**
 * Selected-marker popup: thumb, name, facts, address, the server's foods,
 * "Xem chi tiết". With a food chosen on the map (`foodKey`), a restaurant
 * that has it says so; one that doesn't never mentions that food.
 * `foods` is the catalog as a Map by key (null while loading).
 *
 * variant 'popup' (Goong popup, desktop) or 'sheet' (the phone map's bottom
 * card: full width, 44 px targets, and a router link to the restaurant —
 * `detailLink` { to, state } — instead of the onNavigate callback). Both
 * show exactly the same facts.
 */
export function MapPopupCard({
  restaurant, distanceKm, onNavigate, onClose, foods = null, foodKey = null, variant = 'popup', detailLink = null,
}) {
  const category = categoryOf(restaurant);
  const chosen = matchesFood(restaurant, foodKey) ? foods?.get(foodKey) : null;
  const place = [isKnownCategory(category) && category.label, restaurant.address].filter(Boolean).join(' · ');
  const sheet = variant === 'sheet';

  return (
    <div className={`${sheet ? 'w-full' : 'w-64'} p-3 text-left`}>
      <div className="flex gap-3">
        <RestaurantThumb
          restaurant={restaurant}
          category={category}
          className="h-14 w-14 flex-shrink-0 rounded-field"
        />
        <div className="min-w-0 flex-1">
          <div className="flex items-start gap-1">
            <p className="line-clamp-2 flex-1 text-body font-semibold leading-snug text-ink">
              {restaurant.name}
            </p>
            <button
              type="button"
              onClick={onClose}
              aria-label="Đóng"
              className={`flex-shrink-0 rounded-full text-ink-subtle transition-colors hover:bg-surface-sunken hover:text-ink
                          ${sheet ? '-mr-2 -mt-2 flex h-11 w-11 items-center justify-center' : '-mr-1 -mt-1 p-1'}`}
            >
              <Icon name="x" size={sheet ? 20 : 16} strokeWidth={2} />
            </button>
          </div>
          <RestaurantMeta restaurant={restaurant} distanceKm={distanceKm} size="xs" className="mt-1" />
        </div>
      </div>

      {place && <p className="mt-2 truncate text-label font-normal text-ink-muted">{place}</p>}
      {chosen && (
        <p data-food-note className="mt-2 text-small font-semibold text-brand-800">Có món {chosen.label}</p>
      )}
      <FoodPills foodKeys={restaurant.foodKeys} foods={foods} selectedKey={foodKey} className="mt-2" />

      {sheet && detailLink ? (
        <Link
          to={detailLink.to}
          state={detailLink.state}
          className={buttonClass({ size: 'md', className: 'mt-3 w-full' })}
        >
          Xem chi tiết
        </Link>
      ) : (
        <button
          type="button"
          onClick={onNavigate}
          className={buttonClass({ size: 'sm', className: 'mt-3 w-full' })}
        >
          Xem chi tiết
        </button>
      )}
    </div>
  );
}

/** Hover preview: name plus whatever facts exist. Non-interactive. */
export function MapHoverCard({ restaurant, distanceKm }) {
  return (
    <div className="max-w-[220px] px-3 py-2 text-left">
      <p className="truncate text-small font-semibold text-ink">{restaurant.name}</p>
      <RestaurantMeta
        restaurant={restaurant}
        distanceKm={distanceKm}
        showCategory
        showUnrated={false}
        size="xs"
        className="mt-0.5"
      />
    </div>
  );
}
