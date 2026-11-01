export const STAR_PATH =
  'M9.049 2.927c.3-.921 1.603-.921 1.902 0l1.07 3.292a1 1 0 00.95.69h3.462c.969 0 1.371 1.24.588 1.81l-2.8 2.034a1 1 0 00-.364 1.118l1.07 3.292c.3.921-.755 1.688-1.54 1.118l-2.8-2.034a1 1 0 00-1.175 0l-2.8 2.034c-.784.57-1.838-.197-1.539-1.118l1.07-3.292a1 1 0 00-.364-1.118L2.98 8.72c-.783-.57-.38-1.81.588-1.81h3.461a1 1 0 00.951-.69l1.07-3.292z';

const sizes = { sm: 'w-3.5 h-3.5', md: 'w-4 h-4', lg: 'w-7 h-7' };

function Star({ filled, size }) {
  return (
    <svg
      viewBox="0 0 20 20"
      fill={filled ? 'currentColor' : 'none'}
      stroke="currentColor"
      strokeWidth={1.5}
      aria-hidden="true"
      className={`${sizes[size]} ${filled ? 'text-star' : 'text-ink-subtle/60'}`}
    >
      <path strokeLinejoin="round" d={STAR_PATH} />
    </svg>
  );
}

/**
 * StarRating — read-only display, or an interactive picker when `interactive`.
 * The picker renders real buttons so it is reachable and operable by keyboard.
 */
export default function StarRating({
  value = 0, max = 5, size = 'md', interactive = false, onChange, className = '',
}) {
  const stars = Array.from({ length: max }, (_, i) => i + 1);

  if (interactive) {
    return (
      <span role="group" aria-label="Chọn số sao" className={`inline-flex items-center gap-1 ${className}`}>
        {stars.map((n) => (
          <button
            key={n}
            type="button"
            onClick={() => onChange?.(n)}
            aria-label={`${n} sao`}
            aria-pressed={value === n}
            className="rounded-tag p-0.5 transition-transform duration-instant ease-standard
                       hover:scale-110 active:scale-95"
          >
            <Star filled={n <= value} size={size} />
          </button>
        ))}
      </span>
    );
  }

  const label = Number(value).toLocaleString('vi-VN', { maximumFractionDigits: 1 });
  return (
    <span
      role="img"
      aria-label={`${label} trên ${max} sao`}
      className={`inline-flex items-center gap-0.5 ${className}`}
    >
      {stars.map((n) => (
        <Star key={n} filled={n <= Math.round(value)} size={size} />
      ))}
    </span>
  );
}
