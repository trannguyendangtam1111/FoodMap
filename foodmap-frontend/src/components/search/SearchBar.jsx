import { useEffect, useRef, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { useDebounce } from '../../hooks/useDebounce';
import { useRecentSearches } from '../../hooks/useRecentSearches';
import { restaurantApi } from '../../api/restaurant.api';
import { foodApi } from '../../api/food.api';
import { matchFoods } from '../../api/catalog.api';
import Spinner from '../ui/Spinner';
import Icon from '../ui/Icon';
import { formatRating } from '../../utils/format';

/**
 * SearchBar — real-time autocomplete + full-text search.
 *
 * Props
 * ─────
 * foodSource     'dishes' | 'catalog'
 *                'dishes' (default, the map page): the "Món ăn" section lists
 *                FoodMap menu dishes (GET /foods/search) for the map's dish filter.
 *                'catalog' (site search): it lists the server's catalog foods
 *                (GET /catalog/foods/match → stable keys) — nothing matched here.
 * onDishSelect   (dish)  => void   — 'dishes': user clicked a dish (activates dish-filter mode)
 * onFoodSelect   ({ key, label }) => void — 'catalog': user clicked a food
 * onSearch       (query) => void   — user pressed Enter or selected a recent search
 * onClear        ()      => void   — user clicked the × button (clears search mode)
 * initialQuery   string            — pre-fill input from URL on page load
 * placeholder    string            — also used as the input's accessible name
 * inputId        string            — id for the input, so other controls can focus it
 * size           'md' | 'lg'       — 'lg' for prominent placements (homepage hero)
 * className      string
 *
 * Keyboard shortcuts
 * ──────────────────
 * ArrowDown / ArrowUp  — navigate suggestions / recent searches
 * Enter                — select highlighted item, or execute search for current text
 * Escape               — close dropdown (+ call onClear if input is empty)
 */
export default function SearchBar({
  foodSource = 'dishes',
  onDishSelect,
  onFoodSelect,
  onSearch,
  onClear,
  initialQuery = '',
  placeholder = 'Tìm món, tìm quán…',
  inputId,
  size = 'md',
  className = '',
}) {
  const isLarge = size === 'lg';
  const [query, setQuery]           = useState(initialQuery);
  const [foods, setFoods]           = useState([]);
  const [restaurants, setRests]     = useState([]);
  const [isLoading, setIsLoading]   = useState(false);
  const [isOpen, setIsOpen]         = useState(false);
  const [activeIndex, setActiveIndex] = useState(-1);

  const debouncedQuery = useDebounce(query, 350);
  const { searches: recentSearches, add: addRecent, remove: removeRecent, clearAll: clearAllRecent } = useRecentSearches();

  const wrapperRef = useRef(null);
  const inputRef   = useRef(null);
  const navigate   = useNavigate();

  // ── Autocomplete fetch ────────────────────────────────────────────────────
  useEffect(() => {
    const q = debouncedQuery.trim();
    if (q.length < 2) {
      setFoods([]);
      setRests([]);
      return;
    }

    const controller = new AbortController();
    setIsLoading(true);

    const foodRequest = foodSource === 'catalog'
      ? matchFoods(q).then((match) => match.foods)
      : foodApi.search({ query: q, size: 5 }, { signal: controller.signal }).then((r) => r.data?.content ?? []);

    Promise.allSettled([
      foodRequest,
      restaurantApi.search({ query: q, size: 5 }, { signal: controller.signal }),
    ]).then(([foodResult, restResult]) => {
      if (controller.signal.aborted) return;
      setFoods(foodResult.status === 'fulfilled' ? foodResult.value : []);
      setRests(
        restResult.status === 'fulfilled'
          ? (restResult.value.data?.content ?? [])
          : [],
      );
    }).finally(() => {
      if (!controller.signal.aborted) setIsLoading(false);
    });

    return () => controller.abort();
  }, [debouncedQuery, foodSource]);

  // Reset highlighted item whenever the visible list changes
  useEffect(() => {
    setActiveIndex(-1);
  }, [debouncedQuery]);

  // ── Close dropdown on outside click ──────────────────────────────────────
  useEffect(() => {
    const handler = (e) => {
      if (wrapperRef.current && !wrapperRef.current.contains(e.target)) {
        setIsOpen(false);
        setActiveIndex(-1);
      }
    };
    document.addEventListener('mousedown', handler);
    return () => document.removeEventListener('mousedown', handler);
  }, []);

  // ── Computed display state ────────────────────────────────────────────────
  const q            = query.trim();
  const hasResults   = foods.length > 0 || restaurants.length > 0;

  // Recent searches: shown when input is focused, query is short, and history exists
  const showRecent       = isOpen && q.length < 2 && recentSearches.length > 0;
  // Autocomplete: shown when query is long enough and there are (or are loading) results
  const showSuggestions  = isOpen && q.length >= 2 && (isLoading || hasResults);
  // No-results feedback: query long enough, not loading, nothing came back
  const showNoResults    = isOpen && q.length >= 2 && !isLoading && !hasResults;
  const showDropdown     = showRecent || showSuggestions || showNoResults;

  // Flat list of keyboard-navigable items
  const navItems = showRecent
    ? recentSearches.map((s) => ({ type: 'recent', value: s }))
    : [
        ...foods.map((f) => ({ type: 'dish', value: f })),
        ...restaurants.map((r) => ({ type: 'restaurant', value: r })),
      ];

  // ── Action helpers ────────────────────────────────────────────────────────

  const closeDropdown = () => {
    setIsOpen(false);
    setActiveIndex(-1);
  };

  const executeSearch = (term) => {
    const trimmed = term.trim();
    if (!trimmed) return;
    addRecent(trimmed);
    closeDropdown();
    if (onSearch) {
      onSearch(trimmed);
    } else {
      // Fallback when used without a parent handler — text search lives on the map
      navigate(`/map?q=${encodeURIComponent(trimmed)}`);
    }
  };

  const handleDishClick = (food) => {
    setQuery('');
    setFoods([]);
    setRests([]);
    closeDropdown();
    inputRef.current?.blur();
    if (foodSource === 'catalog') onFoodSelect?.(food);
    else onDishSelect?.(food);
  };

  const handleRestaurantClick = (restaurant) => {
    setQuery('');
    closeDropdown();
    navigate(`/restaurants/${restaurant.id}`);
  };

  const handleRecentClick = (term) => {
    setQuery(term);
    executeSearch(term);
  };

  const handleClear = () => {
    setQuery('');
    setFoods([]);
    setRests([]);
    closeDropdown();
    inputRef.current?.focus();
    onClear?.();
  };

  // ── Form / keyboard handlers ──────────────────────────────────────────────

  const handleSubmit = (e) => {
    e.preventDefault();

    if (activeIndex >= 0 && navItems[activeIndex]) {
      const item = navItems[activeIndex];
      if (item.type === 'recent')     { setQuery(item.value); executeSearch(item.value); }
      else if (item.type === 'dish')       { handleDishClick(item.value); }
      else if (item.type === 'restaurant') { handleRestaurantClick(item.value); }
      return;
    }

    executeSearch(query);
  };

  const handleKeyDown = (e) => {
    switch (e.key) {
      case 'ArrowDown':
        e.preventDefault();
        setIsOpen(true);
        setActiveIndex((i) => Math.min(i + 1, navItems.length - 1));
        break;
      case 'ArrowUp':
        e.preventDefault();
        setActiveIndex((i) => Math.max(i - 1, -1));
        break;
      case 'Escape':
        e.preventDefault();
        closeDropdown();
        if (!q) onClear?.();
        break;
      default:
        break;
    }
  };

  // ── Render ────────────────────────────────────────────────────────────────

  return (
    <div ref={wrapperRef} className={`relative ${className}`}>
      <form onSubmit={handleSubmit} className="flex items-center">
        <div className="relative flex-1">

          {/* Search icon */}
          <span className={`pointer-events-none absolute inset-y-0 flex items-center text-ink-subtle
                            ${isLarge ? 'left-5' : 'left-3.5'}`}>
            <Icon name="search" size={isLarge ? 20 : 16} strokeWidth={2} />
          </span>

          <input
            ref={inputRef}
            id={inputId}
            aria-label={placeholder}
            type="text"
            value={query}
            onChange={(e) => { setQuery(e.target.value); setActiveIndex(-1); }}
            onFocus={() => setIsOpen(true)}
            onKeyDown={handleKeyDown}
            placeholder={placeholder}
            autoComplete="off"
            spellCheck={false}
            className={`w-full rounded-full border border-line bg-surface pr-10 text-ink
                       ${isLarge ? 'h-14 pl-14 text-body-lg shadow-e2' : 'h-10 pl-10 text-body shadow-e1'}
                       outline-none placeholder:text-ink-subtle
                       transition-[border-color,box-shadow] duration-fast ease-standard
                       hover:border-ink-subtle focus:border-brand-600 focus:ring-4 focus:ring-brand-400/25`}
          />

          {/* Right-side: spinner while fetching, clear button when there's text */}
          <span className="absolute inset-y-0 right-3 flex items-center">
            {isLoading ? (
              <Spinner size="sm" />
            ) : query.length > 0 ? (
              <button
                type="button"
                onClick={handleClear}
                aria-label="Xoá tìm kiếm"
                className="rounded-full p-0.5 text-ink-subtle transition-colors hover:text-ink"
              >
                <Icon name="x" size={16} strokeWidth={2} />
              </button>
            ) : null}
          </span>
        </div>
      </form>

      {/* ── Dropdown ─────────────────────────────────────────────────────── */}
      {showDropdown && (
        <div className="absolute z-50 mt-2 max-h-[420px] w-full min-w-[280px] overflow-hidden overflow-y-auto
                        rounded-card border border-line bg-surface shadow-e2">

          {/* Recent searches */}
          {showRecent && (
            <section>
              <div className="flex items-center justify-between px-4 pb-1 pt-3">
                <p className="text-label text-ink-muted">Tìm kiếm gần đây</p>
                <button
                  type="button"
                  onClick={clearAllRecent}
                  className="rounded-tag text-label font-normal text-ink-muted transition-colors hover:text-brand-700"
                >
                  Xoá tất cả
                </button>
              </div>
              <ul>
                {recentSearches.map((term, idx) => (
                  <li key={term} className="group relative">
                    <button
                      type="button"
                      onClick={() => handleRecentClick(term)}
                      className={`flex w-full items-center gap-3 px-4 py-2.5 pr-10 text-left transition-colors
                                  ${activeIndex === idx ? 'bg-brand-50' : 'hover:bg-surface-sunken'}`}
                    >
                      <Icon name="clock" size={16} strokeWidth={2} className="text-ink-subtle" />
                      <span className="min-w-0 flex-1 truncate text-body text-ink">{term}</span>
                    </button>
                    <button
                      type="button"
                      tabIndex={-1}
                      onClick={(e) => { e.stopPropagation(); removeRecent(term); }}
                      aria-label={`Xoá “${term}” khỏi tìm kiếm gần đây`}
                      className="absolute right-3 top-1/2 -translate-y-1/2 rounded-full p-0.5
                                 text-ink-subtle opacity-0 transition-opacity hover:text-ink group-hover:opacity-100"
                    >
                      <Icon name="x" size={14} strokeWidth={2} />
                    </button>
                  </li>
                ))}
              </ul>
            </section>
          )}

          {/* Autocomplete suggestions */}
          {showSuggestions && (
            <>
              {/* Dishes */}
              {foods.length > 0 && (
                <section>
                  <p className="px-4 pb-1 pt-3 text-label text-ink-muted">Món ăn</p>
                  <ul>
                    {foods.map((food, idx) => (
                      <li key={food.key ?? food.id}>
                        <button
                          type="button"
                          onClick={() => handleDishClick(food)}
                          className={`group flex w-full items-center gap-3 px-4 py-2.5 text-left transition-colors
                                      ${activeIndex === idx ? 'bg-brand-50' : 'hover:bg-brand-50'}`}
                        >
                          <span className="flex h-7 w-7 flex-shrink-0 items-center justify-center rounded-full
                                           bg-brand-100 text-brand-800">
                            <Icon name="utensils" size={14} strokeWidth={2} />
                          </span>
                          <span className="min-w-0 flex-1">
                            <span className="block truncate text-body font-medium text-ink">{food.label ?? food.name}</span>
                            {food.cuisine && (
                              <span className="block truncate text-small text-ink-muted">{food.cuisine}</span>
                            )}
                          </span>
                          <span className="flex-shrink-0 text-small font-semibold text-brand-700
                                           opacity-0 transition-opacity group-hover:opacity-100">
                            {foodSource === 'catalog' ? 'Xem quán →' : 'Lọc trên bản đồ →'}
                          </span>
                        </button>
                      </li>
                    ))}
                  </ul>
                </section>
              )}

              {foods.length > 0 && restaurants.length > 0 && (
                <div className="mx-4 border-t border-line" />
              )}

              {/* Restaurants */}
              {restaurants.length > 0 && (
                <section>
                  <p className="px-4 pb-1 pt-3 text-label text-ink-muted">Quán ăn</p>
                  <ul>
                    {restaurants.map((r, idx) => {
                      const itemIdx = foods.length + idx;
                      return (
                        <li key={r.id}>
                          <button
                            type="button"
                            onClick={() => handleRestaurantClick(r)}
                            className={`flex w-full items-start gap-3 px-4 py-2.5 text-left transition-colors
                                        ${activeIndex === itemIdx ? 'bg-brand-50' : 'hover:bg-surface-sunken'}`}
                          >
                            <Icon name="map-pin" size={16} strokeWidth={2} className="mt-0.5 text-brand-700" />
                            <span className="min-w-0 flex-1">
                              <span className="block truncate text-body font-medium text-ink">{r.name}</span>
                              {r.address && (
                                <span className="block max-w-[260px] truncate text-small text-ink-muted">{r.address}</span>
                              )}
                            </span>
                            {r.avgRating > 0 && (
                              <span
                                className="flex-shrink-0 text-small font-semibold tabular-nums text-ink"
                                aria-label={`Đánh giá ${formatRating(r.avgRating)} trên 5`}
                              >
                                <span aria-hidden="true" className="text-star">★</span> {formatRating(r.avgRating)}
                              </span>
                            )}
                          </button>
                        </li>
                      );
                    })}
                  </ul>
                </section>
              )}
            </>
          )}

          {/* No results */}
          {showNoResults && (
            <div className="px-4 py-5 text-center">
              <p className="text-body text-ink-muted">
                Không có kết quả cho <span className="font-semibold text-ink">“{q}”</span>
              </p>
              <p className="mt-1 text-small text-ink-muted">Thử một từ khoá khác nhé</p>
            </div>
          )}

          <div className="h-1" />
        </div>
      )}
    </div>
  );
}
