import { useId } from 'react';
import { buttonClass } from '../ui/Button';
import Icon from '../ui/Icon';
import { directionsUrl } from '../../utils/directions';

/**
 * "Chỉ đường" — opens Google Maps directions to the restaurant in a new tab
 * (utils/directions: the restaurant's own coordinates only, no origin, no
 * user location). Renders nothing when the coordinates are missing or
 * invalid: no broken link, no guessed place.
 *
 * Accessible name "Chỉ đường đến {tên quán}"; the new-tab behaviour is the
 * accessible description (and the trailing icon, visually).
 */
export default function DirectionsLink({ restaurant, variant = 'secondary', className = '' }) {
  const noteId = useId();
  const href = directionsUrl(restaurant);
  if (!href) return null;

  return (
    <>
      <a
        href={href}
        target="_blank"
        rel="noopener noreferrer"
        aria-describedby={noteId}
        data-directions=""
        className={buttonClass({ variant, size: 'md', className })}
      >
        <Icon name="navigation" size={18} strokeWidth={2} />
        Chỉ đường
        {restaurant.name && <span className="sr-only"> đến {restaurant.name}</span>}
        <Icon name="external" size={14} strokeWidth={2} />
      </a>
      <span id={noteId} hidden>Mở Google Maps trong thẻ mới.</span>
    </>
  );
}
