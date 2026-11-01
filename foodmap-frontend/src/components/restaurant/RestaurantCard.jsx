import { Link } from 'react-router-dom';
import Badge from '../ui/Badge';
import RestaurantThumb from './RestaurantThumb';
import RestaurantMeta from './RestaurantMeta';
import { categoryOf, isKnownCategory } from '../../utils/category';

/**
 * RestaurantCard — compact list row (map sidebar): thumb, name, one line of
 * facts, then category + address. Works for every data shape from complete
 * to bare.
 *
 * distanceKm must be the distance from the real user location, or null.
 * (restaurant.distanceKm from rankRestaurants is the map-centre sort key and
 * is deliberately not displayed.)
 *
 * linkState  router state for the detail link (the map passes { fromFood })
 * children   an extra line under the facts (the map's food pills)
 */
export default function RestaurantCard({ restaurant, isActive = false, onClick, distanceKm = null, linkState, children }) {
  const { id, name, address, isFeatured } = restaurant;
  const category = categoryOf(restaurant);
  const place = [isKnownCategory(category) && category.label, address].filter(Boolean).join(' · ');

  return (
    <Link
      to={`/restaurants/${id}`}
      state={linkState}
      onClick={onClick}
      aria-current={isActive ? 'true' : undefined}
      className={`group flex gap-3 rounded-card border bg-surface p-2.5 shadow-e1
                  transition-[border-color,box-shadow,transform] duration-fast ease-standard
                  hover:-translate-y-0.5 hover:shadow-e2
                  ${isActive ? 'border-brand-400 ring-2 ring-brand-200' : 'border-line hover:border-brand-200'}`}
    >
      <div className="relative flex-shrink-0">
        <RestaurantThumb restaurant={restaurant} category={category} className="h-16 w-16 rounded-field" />
        {/* Top-3 of the "Phù hợp" sort (rankRestaurants) — on the thumb so the name keeps its room */}
        {isFeatured && (
          <Badge variant="brand" className="absolute -bottom-1.5 left-1/2 -translate-x-1/2 whitespace-nowrap shadow-e1">
            Gợi ý
          </Badge>
        )}
      </div>

      <div className="min-w-0 flex-1 py-0.5">
        <p className="truncate text-body font-semibold text-ink transition-colors group-hover:text-brand-700">
          {name}
        </p>
        <RestaurantMeta restaurant={restaurant} distanceKm={distanceKm} className="mt-0.5" />
        {place && <p className="mt-0.5 truncate text-small text-ink-muted">{place}</p>}
        {children}
      </div>
    </Link>
  );
}
