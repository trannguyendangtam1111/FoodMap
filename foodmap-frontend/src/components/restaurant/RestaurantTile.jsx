import { Link } from 'react-router-dom';
import Chip from '../ui/Chip';
import RestaurantThumb from './RestaurantThumb';
import RestaurantMeta from './RestaurantMeta';
import { categoryOf, isKnownCategory } from '../../utils/category';

/**
 * RestaurantTile — vertical presentation for horizontal rails and compact
 * grids: 4:3 thumb with the category chip, name, one line of facts.
 * Width comes from the parent (className).
 *
 * distanceKm must be the distance from the real user location, or null.
 */
export default function RestaurantTile({ restaurant, distanceKm = null, className = '' }) {
  const category = categoryOf(restaurant);

  return (
    <Link to={`/restaurants/${restaurant.id}`} className={`group block rounded-tile ${className}`}>
      <div className="relative">
        <RestaurantThumb
          restaurant={restaurant}
          category={category}
          className="aspect-[4/3] w-full rounded-tile border border-line
                     transition-[box-shadow,transform] duration-fast ease-standard
                     group-hover:-translate-y-0.5 group-hover:shadow-e2"
        />
        {isKnownCategory(category) && (
          <Chip tone={category.tone} className="absolute left-2 top-2 shadow-e1">
            {category.label}
          </Chip>
        )}
      </div>

      <p className="mt-2 line-clamp-2 text-body font-semibold text-ink transition-colors group-hover:text-brand-700">
        {restaurant.name}
      </p>
      <RestaurantMeta restaurant={restaurant} distanceKm={distanceKm} className="mt-1" />
    </Link>
  );
}
