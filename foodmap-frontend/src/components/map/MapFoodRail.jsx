import { useEffect, useRef } from 'react';
import FoodArt from '../art/FoodArt';
import Skeleton from '../ui/Skeleton';
import RetryNotice from '../food/RetryNotice';

/**
 * The food rail of the map's "Món" mode: the catalog's discoverable foods,
 * in catalog order (never re-ordered by count), each a toggle button with its
 * art, label and how many loaded restaurants in the map frame have it.
 *
 * A food with none in the frame stays selectable, drawn lower-contrast and
 * without a badge. Pressing the selected food again clears it.
 *
 *   status     useCatalog status ('idle' | 'loading' | 'ready' | 'error')
 *   foods      discoverable catalog foods, catalog order
 *   counts     Map foodKey → count in the current viewport payload
 */
export default function MapFoodRail({ status, foods, counts, selectedKey, onSelect, error, onRetry }) {
  // The chosen food stays in sight on a narrow rail (deep link, Back, a
  // rotation that changes the rail's width): the rail scrolls sideways only —
  // never the page.
  const railRef = useRef(null);
  useEffect(() => {
    const reveal = () => {
      const rail = railRef.current;
      const chip = selectedKey && rail?.querySelector(`[data-food-chip="${selectedKey}"]`)?.parentElement;
      if (!chip) return;
      const left = chip.offsetLeft;   // the rail is the chips' offsetParent (relative)
      if (left < rail.scrollLeft || left + chip.offsetWidth > rail.scrollLeft + rail.clientWidth) {
        rail.scrollLeft = Math.max(0, left - (rail.clientWidth - chip.offsetWidth) / 2);
      }
    };
    reveal();
    if (!railRef.current || typeof ResizeObserver === 'undefined') return undefined;
    const observer = new ResizeObserver(reveal);   // after layout, unlike a window 'resize'
    observer.observe(railRef.current);
    return () => observer.disconnect();
  }, [selectedKey, status]);

  if (status === 'error') {
    return (
      <div className="border-b border-line bg-surface px-3 py-3">
        <RetryNotice title="Chưa tải được danh sách món." error={error} onRetry={onRetry} className="p-3" />
      </div>
    );
  }

  if (status !== 'ready') {
    return (
      <div className="border-b border-line bg-surface px-3 py-2" aria-busy="true">
        <p className="sr-only">Đang tải danh sách món…</p>
        <ul className="flex gap-2 overflow-hidden" aria-hidden="true">
          {Array.from({ length: 6 }, (_, i) => (
            <li key={i} data-rail-skeleton className="flex w-[4.25rem] flex-shrink-0 flex-col items-center gap-1 py-1">
              <Skeleton className="h-11 w-11 rounded-full" />
              <Skeleton className="h-3 w-12" />
            </li>
          ))}
        </ul>
      </div>
    );
  }

  return (
    <div className="border-b border-line bg-surface">
      <ul
        ref={railRef}
        aria-label="Chọn món"
        className="relative flex snap-x gap-1 overflow-x-auto overscroll-x-contain scroll-px-3 px-3 pb-2 pt-2"
      >
        {foods.map((food) => {
          const count = counts.get(food.key) ?? 0;
          const pressed = food.key === selectedKey;
          return (
            <li key={food.key} className="flex-shrink-0 snap-start">
              <button
                type="button"
                data-food-chip={food.key}
                aria-pressed={pressed}
                aria-label={`${food.label}, ${count > 0 ? `${count} quán` : 'chưa có quán'} trong khung bản đồ`}
                onClick={() => onSelect(pressed ? null : food.key)}
                className={`flex min-h-11 w-[4.25rem] flex-col items-center gap-1 rounded-tile border-2 px-1 py-1
                            text-center transition-colors duration-fast ease-standard
                            focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-2
                            focus-visible:outline-brand-700
                            ${pressed ? 'border-ink bg-brand-50' : 'border-transparent hover:bg-surface-sunken'}`}
              >
                <span className="relative">
                  <FoodArt
                    artKey={food.artKey}
                    parentCategory={food.parentCategory}
                    tone={food.tone}
                    className={`h-11 w-11 rounded-full border border-line ${count > 0 || pressed ? '' : 'opacity-50'}`}
                  />
                  {count > 0 && (
                    <span
                      aria-hidden="true"
                      data-count-badge
                      className="absolute -right-1.5 -top-1 flex h-5 min-w-5 items-center justify-center rounded-full
                                 border-2 border-surface bg-ink px-1 text-[0.6875rem] font-bold leading-none text-white"
                    >
                      {count > 99 ? '99+' : count}
                    </span>
                  )}
                </span>
                <span
                  className={`w-full truncate text-label font-semibold
                              ${count > 0 || pressed ? 'text-ink' : 'font-medium text-ink-muted'}`}
                >
                  {food.label}
                </span>
              </button>
            </li>
          );
        })}
      </ul>
    </div>
  );
}
