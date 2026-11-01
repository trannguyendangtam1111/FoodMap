import { useState } from 'react';
import CategoryArt from './CategoryArt';
import { categoryOf } from '../../utils/category';

/**
 * RestaurantThumb — the restaurant's real photo when one exists, otherwise
 * its CategoryArt. A photo that fails to load also falls back to the art, so
 * there is never a broken-image icon and never an invented photo.
 *
 * The caller sets size, aspect ratio and radius via className; the box is
 * sized before anything loads, so there is no layout shift.
 */
export default function RestaurantThumb({
  restaurant, category, pattern = false, className = '',
  artClassName = '', // e.g. padding that keeps the art clear of overlapping content
}) {
  const photo = restaurant?.photos?.find((p) => typeof p === 'string' && p.trim() !== '');
  const [failedSrc, setFailedSrc] = useState(null);
  const showPhoto = photo && failedSrc !== photo;

  return (
    <div className={`relative overflow-hidden bg-surface-sunken ${className}`}>
      {showPhoto ? (
        <img
          src={photo}
          alt={`Ảnh quán ${restaurant.name}`}
          loading="lazy"
          decoding="async"
          onError={() => setFailedSrc(photo)}
          className="absolute inset-0 h-full w-full object-cover"
        />
      ) : (
        <CategoryArt
          category={category ?? categoryOf(restaurant)}
          pattern={pattern}
          className={`absolute inset-0 h-full w-full ${artClassName}`}
        />
      )}
    </div>
  );
}
