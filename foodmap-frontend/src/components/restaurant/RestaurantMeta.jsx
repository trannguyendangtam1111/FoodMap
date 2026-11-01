import Icon from '../ui/Icon';
import { STAR_PATH } from '../ui/StarRating';
import { formatPriceLevel, formatRating } from '../../utils/format';
import { formatDistance } from '../../utils/ranking';
import { openingStatus } from '../../utils/openingStatus';
import { categoryOf, isKnownCategory } from '../../utils/category';

/**
 * RestaurantMeta — one compact line of facts, in priority order:
 *   distance · open/closed · rating · price · category
 *
 * Every item renders only when the data behind it exists. Nothing is padded
 * with placeholders: no "★ 0.0", no price symbols without a price level, no
 * "Đang mở" without opening hours.
 *
 * Props
 * ─────
 * restaurant   summary or detail DTO
 * distanceKm   distance from the *real* user location, or null (see
 *              distanceFromUserKm) — never the map-centre sort distance
 * category     optional pre-computed categoryOf() result
 * showCategory include the category label (skipped for unknown categories)
 * showUnrated  say "Chưa có đánh giá" when there is no rating
 * size         'sm' (default) | 'xs'
 */
export default function RestaurantMeta({
  restaurant,
  distanceKm = null,
  category,
  showCategory = false,
  showUnrated = true,
  size = 'sm',
  className = '',
}) {
  if (!restaurant) return null;
  const { avgRating, reviewCount, priceLevel } = restaurant;
  const status = openingStatus(restaurant);
  const distance = formatDistance(distanceKm);
  const price = formatPriceLevel(priceLevel);
  const rated = Number(avgRating) > 0;

  const items = [];

  if (distance) {
    items.push(
      <span key="distance" className="inline-flex items-center gap-0.5 font-semibold text-ink">
        <Icon name="map-pin" size={size === 'xs' ? 12 : 14} strokeWidth={2} />
        <span className="sr-only">Cách bạn </span>
        {distance}
      </span>,
    );
  }

  if (status.state !== 'unknown') {
    const open = status.state === 'open';
    items.push(
      <span
        key="status"
        className={`inline-flex items-center gap-1 font-semibold ${open ? 'text-success-ink' : 'text-danger-ink'}`}
      >
        <span aria-hidden="true" className={`h-1.5 w-1.5 rounded-full ${open ? 'bg-success' : 'bg-danger'}`} />
        {status.label}
      </span>,
    );
  }

  if (rated) {
    const ratingText = formatRating(avgRating);
    items.push(
      <span
        key="rating"
        className="inline-flex items-center gap-0.5"
        aria-label={`Đánh giá ${ratingText} trên 5${reviewCount > 0 ? `, ${reviewCount} lượt` : ''}`}
      >
        <svg viewBox="0 0 20 20" fill="currentColor" aria-hidden="true"
             className={`${size === 'xs' ? 'h-3 w-3' : 'h-3.5 w-3.5'} text-star`}>
          <path d={STAR_PATH} />
        </svg>
        <span aria-hidden="true" className="font-semibold tabular-nums text-ink">{ratingText}</span>
        {reviewCount > 0 && <span aria-hidden="true" className="tabular-nums">({reviewCount})</span>}
      </span>,
    );
  } else if (showUnrated) {
    items.push(<span key="unrated">Chưa có đánh giá</span>);
  }

  if (price) {
    items.push(
      <span key="price" aria-label={`Mức giá ${priceLevel} trên 4`}>
        <span aria-hidden="true">{price}</span>
      </span>,
    );
  }

  const cat = showCategory ? (category ?? categoryOf(restaurant)) : null;
  if (isKnownCategory(cat)) {
    items.push(<span key="category">{cat.label}</span>);
  }

  if (items.length === 0) return null;

  // Items are told apart by their own marks (pin, status dot, star, ₫), so
  // they are separated by spacing alone — no "·" left dangling when it wraps.
  return (
    <p
      className={`flex flex-wrap items-center gap-x-2.5 gap-y-0.5 text-ink-muted
                  ${size === 'xs' ? 'text-label font-normal' : 'text-small'} ${className}`}
    >
      {items}
    </p>
  );
}
