import { useEffect, useState } from 'react';

const matches = (query, fallback) =>
  typeof window !== 'undefined' && typeof window.matchMedia === 'function'
    ? window.matchMedia(query).matches
    : fallback;

/**
 * True while `query` matches; follows live changes (rotation, resize).
 * `fallback` is used where matchMedia doesn't exist (tests, old browsers).
 */
export default function useMediaQuery(query, fallback = false) {
  const [matched, setMatched] = useState(() => matches(query, fallback));

  useEffect(() => {
    if (typeof window.matchMedia !== 'function') return undefined;
    const mql = window.matchMedia(query);
    const onChange = () => setMatched(mql.matches);
    onChange();
    mql.addEventListener?.('change', onChange);
    return () => mql.removeEventListener?.('change', onChange);
  }, [query]);

  return matched;
}

/** Below Tailwind's `md` — the phone layout of the map. */
export const MOBILE_QUERY = '(max-width: 767px)';
