import { useEffect, useRef } from 'react';
import { Link } from 'react-router-dom';
import Button from '../ui/Button';
import Icon from '../ui/Icon';
import Skeleton from '../ui/Skeleton';
import RestaurantThumb from './RestaurantThumb';
import RestaurantMeta from './RestaurantMeta';
import { categoryOf } from '../../utils/category';

/**
 * FoodRestaurantList — the food page's list: the restaurants FoodMap knows to
 * have the food, exactly in the server's order (its ranking; never re-sorted,
 * never numbered as a rank). Each row says why it is listed — the server's
 * evidence (the restaurant's name, or a dish on its FoodMap menu) — and shows
 * only facts that exist: a distance only for the user's own location (the
 * server sends none otherwise), a rating only with reviews.
 *
 * "Xem thêm quán" appends the server's next page; focus then moves to the
 * first new restaurant, so keyboard users continue where the list grew.
 */

/** Why the restaurant is listed for the food, from the server's evidence. */
export function evidenceText(evidence, foodLabel) {
  if (evidence?.source === 'MENU' && evidence.dishName) return `Thực đơn có ${evidence.dishName}`;
  if (evidence?.source === 'NAME' && foodLabel) return `Tên quán cho thấy có ${foodLabel.toLocaleLowerCase('vi')}`;
  return null;
}

export default function FoodRestaurantList({ items, foodKey, foodLabel, labelledBy, hasMore, loadingMore, moreError, onLoadMore }) {
  const listRef = useRef(null);
  const focusFrom = useRef(null);

  useEffect(() => {
    if (focusFrom.current == null || items.length <= focusFrom.current) return;
    listRef.current?.children[focusFrom.current]?.querySelector('a')?.focus();
    focusFrom.current = null;
  }, [items.length]);

  const loadMore = () => {
    if (loadingMore) return;
    focusFrom.current = items.length;
    onLoadMore();
  };

  return (
    <>
      <ul ref={listRef} aria-labelledby={labelledBy} className="grid gap-3 md:grid-cols-2">
        {items.map((item) => (
          <li key={item.restaurant.id} className="min-w-0">
            <FoodRestaurantRow item={item} foodKey={foodKey} foodLabel={foodLabel} />
          </li>
        ))}
      </ul>
      {moreError && (
        <p className="mt-3 text-small text-ink-muted">Chưa tải thêm được quán, bạn thử lại nhé.</p>
      )}
      {hasMore && (
        <Button
          variant="secondary"
          onClick={loadMore}
          aria-disabled={loadingMore || undefined}
          className={`mt-4 w-full scroll-mb-bottom-nav sm:w-auto ${loadingMore ? 'opacity-60' : ''}`}
        >
          {loadingMore ? 'Đang tải thêm quán…' : 'Xem thêm quán'}
        </Button>
      )}
    </>
  );
}

function FoodRestaurantRow({ item, foodKey, foodLabel }) {
  const { restaurant, distanceMeters, evidence } = item;
  const why = evidenceText(evidence, foodLabel);
  return (
    <Link
      to={`/restaurants/${restaurant.id}`}
      // the detail page names this food first in "Quán này có" (if the server matched it)
      state={foodKey ? { fromFood: foodKey } : undefined}
      className="group flex h-full scroll-mb-bottom-nav gap-3 rounded-card border border-line bg-surface p-3 shadow-e1
                 transition-[border-color,box-shadow,transform] duration-fast ease-standard
                 hover:-translate-y-0.5 hover:border-brand-200 hover:shadow-e2"
    >
      <RestaurantThumb
        restaurant={restaurant}
        category={categoryOf(restaurant)}
        className="h-16 w-16 flex-shrink-0 rounded-field sm:h-20 sm:w-20"
      />
      <div className="min-w-0 flex-1">
        <p className="line-clamp-2 break-words text-body font-semibold text-ink transition-colors group-hover:text-brand-700">
          {restaurant.name}
        </p>
        <RestaurantMeta
          restaurant={restaurant}
          distanceKm={distanceMeters != null ? distanceMeters / 1000 : null}
          showUnrated={false}
          className="mt-0.5"
        />
        {restaurant.address && (
          <p className="mt-0.5 line-clamp-1 break-words text-small text-ink-muted">{restaurant.address}</p>
        )}
        {why && (
          <p className="mt-1.5 flex items-start gap-1 text-small font-medium text-brand-800">
            <Icon name="utensils" size={14} className="mt-0.5 flex-shrink-0" />
            <span className="min-w-0 break-words">{why}</span>
          </p>
        )}
      </div>
    </Link>
  );
}

/** Same footprint as the rows: nothing jumps when they arrive. */
export function FoodRestaurantListSkeleton({ rows = 4 }) {
  return (
    <ul aria-hidden="true" className="grid gap-3 md:grid-cols-2">
      {Array.from({ length: rows }, (_, i) => (
        <li key={i} className="flex gap-3 rounded-card border border-line bg-surface p-3">
          <Skeleton className="h-16 w-16 flex-shrink-0 rounded-field sm:h-20 sm:w-20" />
          <span className="min-w-0 flex-1">
            <Skeleton className="h-4 w-3/4" />
            <Skeleton className="mt-2 h-3.5 w-1/2" />
            <Skeleton className="mt-2 h-3.5 w-2/3" />
          </span>
        </li>
      ))}
    </ul>
  );
}
