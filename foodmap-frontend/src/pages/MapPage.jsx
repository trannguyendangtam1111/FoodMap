import { useCallback, useEffect, useLayoutEffect, useMemo, useRef, useState } from 'react';
import { Link, useNavigationType, useSearchParams } from 'react-router-dom';
import MapView from '../components/map/MapView';
import SearchBar from '../components/search/SearchBar';
import AppHeader from '../components/layout/AppHeader';
import { SEARCH_INPUT_ID, SEARCH_PLACEHOLDER } from '../components/search/SiteSearch';
import MobileBottomNav from '../components/layout/MobileBottomNav';
import ActiveDishFilter from '../components/search/ActiveDishFilter';
import RestaurantCard from '../components/restaurant/RestaurantCard';
import RestaurantCardSkeleton from '../components/restaurant/RestaurantCardSkeleton';
import ScopeBar from '../components/location/ScopeBar';
import FoodArt from '../components/art/FoodArt';
import RetryNotice from '../components/food/RetryNotice';
import FoodGlyphSprite from '../components/map/FoodGlyphSprite';
import FoodPills from '../components/map/FoodPills';
import MapFoodRail from '../components/map/MapFoodRail';
import { MapPopupCard } from '../components/map/MapPopupCard';
import { recallMap, rememberMap } from '../components/map/mapMemory';
import { useGeolocation } from '../hooks/useGeolocation';
import { useViewportRestaurants } from '../hooks/useViewportRestaurants';
import useCatalog from '../hooks/useCatalog';
import useLocationScope from '../hooks/useLocationScope';
import useMediaQuery, { MOBILE_QUERY } from '../hooks/useMediaQuery';
import { distanceFromUserKm, rankRestaurants } from '../utils/ranking';
import { countFoods, foodCountText, matchesFood } from '../utils/mapFood';
import { scopeSearch } from '../utils/scope';
import Icon from '../components/ui/Icon';

// Sorting is relative to the map centre (where the user is looking), which is
// why "Gần nhất" carries a clarifying tooltip.
const SORT_OPTIONS = [
  { value: 'best',      label: 'Phù hợp',  title: 'Phù hợp nhất' },
  { value: 'closest',   label: 'Gần nhất', title: 'Gần tâm bản đồ nhất' },
  { value: 'top_rated', label: 'Điểm cao', title: 'Đánh giá cao nhất' },
];
// "Món" mode adds no rating surface: the rating sort is not offered there.
const FOOD_SORT_OPTIONS = SORT_OPTIONS.filter((opt) => opt.value !== 'top_rated');

// How the map is read: by restaurant (the original map) or by food.
const MODE_OPTIONS = [
  { value: 'restaurants', label: 'Quán' },
  { value: 'food',        label: 'Món' },
];

// The settled count is announced once the map has been still this long.
const ANNOUNCE_DELAY_MS = 700;

// Desktop sidebar (unchanged) and the phone layout's two presentations.
const ASIDE_DESKTOP = 'flex w-64 flex-shrink-0 flex-col border-r border-line bg-cream sm:w-80 lg:w-96 overflow-hidden';
// List: over the whole map area (the map stays mounted and keeps its size).
const ASIDE_MOBILE_LIST = 'absolute inset-0 z-20 flex flex-col overflow-hidden bg-cream';
// Map: a bottom panel over the map. pb-6 clears the bottom nav's raised button.
const ASIDE_MOBILE_MAP = `absolute inset-x-0 bottom-0 z-20 flex max-h-[75%] flex-col overflow-y-auto overscroll-contain
                          rounded-t-panel border-t border-line bg-cream pb-6 shadow-e3`;

/**
 * /map — restaurants in the map frame, readable by restaurant ("Quán") or by
 * food ("Món", /map?food={key}).
 *
 * Food facts are the server's: the catalog's discoverable foods (labels, art,
 * order) and each restaurant's `foodKeys`. Choosing a food re-reads the
 * restaurants already loaded — no request, the map doesn't move — and every
 * count says it is "trong khung bản đồ". `q` (text search) and `food` never
 * coexist. Nothing about the map's position goes into the URL; Back from a
 * restaurant restores it from memory (mapMemory).
 *
 * Phones (below md): the map fills the page and the controls sit in a bottom
 * panel — mode, food rail, count — with a "Danh sách" / "Bản đồ" switch. The
 * list covers the map without unmounting or resizing it, so switching never
 * fetches. A selected restaurant opens a bottom card instead of a Goong popup.
 * Desktop keeps the sidebar.
 */
export default function MapPage() {
  const navigationType = useNavigationType();
  const [searchParams, setSearchParams] = useSearchParams();
  const urlQuery = searchParams.get('q');
  const urlFood = searchParams.get('food');
  // q wins: a food in the same URL is ignored (and dropped on mount)
  const requestedFoodKey = urlQuery ? null : urlFood;

  // Back to the map (a POP with something remembered in this tab) resumes it.
  const [restored] = useState(() => (navigationType === 'POP' ? recallMap() : null));

  const { location: userLocation, isFallback } = useGeolocation();
  // Distances shown to the user are measured from their real position only
  const hasRealLocation = !!userLocation && !isFallback;

  const {
    restaurants,
    isLoading,
    error,
    indexingCells,
    searchQuery,
    mapCenter,
    setViewport,
    setActiveFoodId,
    setSearchQuery,
    clearFoodFilter,
    clearSearch,
    retry,
  } = useViewportRestaurants({ restore: restored && !urlQuery ? restored.data ?? null : null });

  const [mode, setMode] = useState(() => {
    if (urlQuery) return 'restaurants';
    if (urlFood) return 'food';
    return restored?.mode ?? 'restaurants';
  });

  // Sort mode for the discovery sidebar. Persists across viewport changes.
  const [sortMode, setSortMode] = useState(() => restored?.sortMode ?? 'best');
  const effectiveSort = mode === 'food' && sortMode === 'top_rated' ? 'best' : sortMode;

  // ── Phone layout ──────────────────────────────────────────────────────────
  const isMobile = useMediaQuery(MOBILE_QUERY, false);
  const [mobileView, setMobileView] = useState(() => restored?.mobileView ?? 'map');
  const listView = isMobile && mobileView === 'list';
  const panelView = isMobile && mobileView === 'map';

  // ── The server's food vocabulary ──────────────────────────────────────────
  const catalogState = useCatalog();
  const catalog = catalogState.status === 'ready' ? catalogState.catalog : null;
  const foods = useMemo(() => (catalog ? new Map(catalog.foods.map((f) => [f.key, f])) : null), [catalog]);
  const railFoods = useMemo(() => (catalog ? catalog.foods.filter((f) => f.discoverable) : []), [catalog]);
  const selectedFood = mode === 'food' && requestedFoodKey && foods?.get(requestedFoodKey)?.discoverable
    ? foods.get(requestedFoodKey)
    : null;
  const foodKey = selectedFood?.key ?? null;

  // Restore a search query that was in the URL when the page loaded (e.g. bookmark / back-nav)
  useEffect(() => {
    const q = searchParams.get('q');
    if (q) setSearchQuery(q);
    // q and food are exclusive; q wins
    if (q && searchParams.get('food')) setSearchParams({ q }, { replace: true });
    // setSearchQuery is a stable state-setter; this effect runs once on mount.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  // A food in the URL (deep link, Back/Forward) means "Món" mode.
  useEffect(() => {
    if (requestedFoodKey) setMode('food');
  }, [requestedFoodKey]);

  // A key the catalog doesn't offer is dropped quietly; "Món" mode stays.
  useEffect(() => {
    if (catalog && requestedFoodKey && !foods.get(requestedFoodKey)?.discoverable) {
      setSearchParams({}, { replace: true });
    }
  }, [catalog, foods, requestedFoodKey, setSearchParams]);

  // Auto-dismiss "indexing this area…" toast after 4 s
  const [indexingVisible, setIndexingVisible] = useState(false);
  useEffect(() => {
    if (indexingCells > 0) {
      setIndexingVisible(true);
      const t = setTimeout(() => setIndexingVisible(false), 4000);
      return () => clearTimeout(t);
    }
  }, [indexingCells]);

  // activeDish holds the full FoodSummaryDTO for the chip label.
  // activeFoodId (inside the hook) drives the actual data fetch.
  const [activeDish, setActiveDish]     = useState(null);
  const [selectedId, setSelectedId]     = useState(() => (restored && !urlQuery ? restored.selectedId ?? null : null));

  const mapViewRef = useRef(null);

  // ── Ranked lists ──────────────────────────────────────────────────────────

  // Ranked + distance-annotated list. The same array feeds both the sidebar
  // and the map — markers reconcile by id, so order doesn't affect them.
  const rankedRestaurants = useMemo(
    () => rankRestaurants(restaurants, mapCenter, effectiveSort),
    [restaurants, mapCenter, effectiveSort],
  );

  // How many loaded restaurants carry each food (one pass)
  const foodCounts = useMemo(() => countFoods(restaurants), [restaurants]);

  // "Món" with a food: the restaurants that have it first, the others apart
  const { matches, others } = useMemo(() => {
    if (!foodKey) return { matches: [], others: [] };
    const has = [];
    const hasNot = [];
    restaurants.forEach((r) => (matchesFood(r, foodKey) ? has : hasNot).push(r));
    return {
      matches: rankRestaurants(has, mapCenter, effectiveSort),
      // "Gợi ý" belongs to the restaurants that have the food
      others: rankRestaurants(hasNot, mapCenter, effectiveSort).map(({ isFeatured, ...r }) => r),
    };
  }, [restaurants, foodKey, mapCenter, effectiveSort]);

  // The selected restaurant, as loaded (the phone's bottom card)
  const selectedRestaurant = useMemo(
    () => (selectedId == null ? null : restaurants.find((r) => r.id === selectedId) ?? null),
    [restaurants, selectedId],
  );

  // A selection that doesn't have the newly chosen food is let go.
  useEffect(() => {
    if (!foodKey || selectedId == null) return;
    const selected = restaurants.find((r) => r.id === selectedId);
    if (selected && !matchesFood(selected, foodKey)) setSelectedId(null);
    // only when the food changes
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [foodKey]);

  // ── Memory for Back (this tab only, never storage or URL) ─────────────────
  useEffect(() => {
    rememberMap({ mode, sortMode, selectedId, mobileView });
  }, [mode, sortMode, selectedId, mobileView]);

  useEffect(() => {
    if (!searchQuery && !activeDish && !isLoading && !error) {
      rememberMap({ data: { restaurants, center: mapCenter } });
    }
  }, [restaurants, mapCenter, searchQuery, activeDish, isLoading, error]);

  // ── Search handlers ───────────────────────────────────────────────────────

  // Called when the user presses Enter or selects a recent search
  const handleSearch = useCallback((query) => {
    const trimmed = query.trim();
    if (!trimmed) return;
    setActiveDish(null);
    clearFoodFilter();
    setSearchQuery(trimmed);
    setSelectedId(null);
    setMode('restaurants');           // search results are not the map frame
    setSearchParams({ q: trimmed });  // drops any food
  }, [clearFoodFilter, setSearchQuery, setSearchParams]);

  // Called when the user clicks the × clear button in the search bar
  const handleClearSearch = useCallback(() => {
    clearSearch();
    setSelectedId(null);
    setSearchParams({}, { replace: true });
  }, [clearSearch, setSearchParams]);

  // ── Mode / food handlers ──────────────────────────────────────────────────

  const handleModeChange = useCallback((next) => {
    if (next === mode) return;
    setMode(next);
    if (next === 'food') {
      // "Món" reads the map frame's restaurants: a search or the dish filter
      // (other lists) ends here. Without either, nothing is fetched.
      if (searchQuery) {
        clearSearch();
        setSearchParams({}, { replace: true });
      }
      if (activeDish) {
        setActiveDish(null);
        clearFoodFilter();
      }
    } else if (urlFood) {
      setSearchParams({}, { replace: true });
    }
  }, [mode, searchQuery, activeDish, urlFood, clearSearch, clearFoodFilter, setSearchParams]);

  // Choosing a food rewrites the URL only — the restaurants already loaded are
  // re-read. A change of food replaces the history entry.
  const handleFoodSelect = useCallback((key) => {
    if (!key) {
      setSearchParams({}, { replace: true });
      return;
    }
    setSearchParams({ food: key }, { replace: Boolean(requestedFoodKey) });
  }, [requestedFoodKey, setSearchParams]);

  // "Bỏ chọn món": focus goes back to that food's chip in the rail.
  const refocusChipRef = useRef(null);
  const handleClearFood = useCallback(() => {
    refocusChipRef.current = foodKey;
    handleFoodSelect(null);
  }, [foodKey, handleFoodSelect]);
  useEffect(() => {
    const key = refocusChipRef.current;
    if (!key || foodKey) return;
    refocusChipRef.current = null;
    // food keys are server identifiers ([a-z0-9-]), safe inside the selector
    document.querySelector(`[data-food-chip="${key}"]`)?.focus();
  }, [foodKey]);

  // ── Map / sidebar handlers ────────────────────────────────────────────────

  const handleViewportChange = useCallback((bounds, view) => {
    setViewport(bounds);
    if (view) rememberMap({ view });
  }, [setViewport]);

  const handleMarkerClick = useCallback((restaurant) => {
    setSelectedId(restaurant.id);
    const card = document.getElementById(`card-${restaurant.id}`);
    // a restaurant without the chosen food sits in the folded "Quán khác" list
    const folded = card?.closest('details');
    if (folded && !folded.open) folded.open = true;
    card?.scrollIntoView({ behavior: 'smooth', block: 'nearest' });
  }, []);

  const handleDishSelect = useCallback((food) => {
    setActiveDish(food);
    clearSearch();               // exit search mode before entering dish-filter mode
    setActiveFoodId(food.id);
    setSelectedId(null);
    setMode('restaurants');      // the dish filter belongs to "Quán"
    setSearchParams({}, { replace: true });
  }, [setActiveFoodId, clearSearch, setSearchParams]);

  const handleClearDish = useCallback(() => {
    setActiveDish(null);
    clearFoodFilter();
    setSelectedId(null);
  }, [clearFoodFilter]);

  // Clears selection when the user clicks the empty map canvas
  const handleDeselect = useCallback(() => setSelectedId(null), []);

  const handleZoomOut = useCallback(() => mapViewRef.current?.zoomOut(), []);

  // ── Phone layout: Map ⇄ List, the bottom panel ────────────────────────────

  // The list keeps its scroll position across switches; focus follows the
  // switch button (it moves between the panel and the list).
  const listRef = useRef(null);
  const listScrollRef = useRef(0);
  const focusToggleRef = useRef(false);
  const handleViewToggle = useCallback(() => {
    if (mobileView === 'list') listScrollRef.current = listRef.current?.scrollTop ?? 0;
    focusToggleRef.current = true;
    setMobileView((view) => (view === 'list' ? 'map' : 'list'));
  }, [mobileView]);
  useLayoutEffect(() => {
    if (listView && listRef.current) listRef.current.scrollTop = listScrollRef.current;
    if (focusToggleRef.current) {
      focusToggleRef.current = false;
      document.querySelector('[data-view-toggle]')?.focus();
    }
  }, [listView, mobileView]);

  // The panel's height: the map's own bottom controls sit above it, and a
  // selected pin is panned into the part of the map that is still visible.
  const asideRef = useRef(null);
  const [panelHeight, setPanelHeight] = useState(0);
  useEffect(() => {
    const el = asideRef.current;
    if (!panelView || !el || typeof ResizeObserver === 'undefined') {
      setPanelHeight(0);
      return undefined;
    }
    const observer = new ResizeObserver(() => setPanelHeight(Math.round(el.getBoundingClientRect().height)));
    observer.observe(el);
    return () => observer.disconnect();
  }, [panelView]);
  const selectionOffset = useMemo(
    () => (isMobile ? [0, -Math.round(panelHeight / 2)] : undefined),
    [isMobile, panelHeight],
  );

  // Behind the list the map is out of reach for keyboard and screen readers.
  const mapAreaRef = useRef(null);
  useEffect(() => {
    const el = mapAreaRef.current;
    if (!el) return;
    if (listView) el.setAttribute('inert', '');
    else el.removeAttribute('inert');
  }, [listView]);

  // Escape closes the phone's bottom card
  useEffect(() => {
    if (!panelView || selectedId == null) return undefined;
    const onKey = (e) => { if (e.key === 'Escape') setSelectedId(null); };
    document.addEventListener('keydown', onKey);
    return () => document.removeEventListener('keydown', onKey);
  }, [panelView, selectedId]);

  // ── Sidebar label ─────────────────────────────────────────────────────────

  const count = rankedRestaurants.length;
  // Cold-region: backend just enqueued cells AND DB has nothing → say
  // "indexing" instead of the misleading "0 restaurants in view".
  const isColdRegion = count === 0 && indexingCells > 0 && !searchQuery && !activeDish;
  const sidebarLabel = isLoading
    ? (searchQuery ? `Đang tìm “${searchQuery}”…` : 'Đang tải quán ăn…')
    : error && count === 0
    ? null                                   // the one error message is below
    : searchQuery
    ? `${count} kết quả cho “${searchQuery}”`
    : activeDish
    ? `${count} quán có món ${activeDish.name}`
    : isColdRegion
    ? 'Đang cập nhật khu vực này…'
    : `${count} quán trong khung bản đồ`;

  const foodCount = foodKey ? foodCountText(matches.length, selectedFood.label) : null;

  // What the screen reader hears: the settled count only, never "loading" on
  // every pan. Back to the map starts with it already said.
  const settledText = isLoading ? null : (foodCount ?? sidebarLabel);
  const [announced, setAnnounced] = useState(() => (restored ? settledText ?? '' : ''));
  useEffect(() => {
    if (settledText == null) return undefined;
    const t = setTimeout(() => setAnnounced(settledText), ANNOUNCE_DELAY_MS);
    return () => clearTimeout(t);
  }, [settledText]);

  const { scope } = useLocationScope();
  const foodPageHref = foodKey ? `/mon/${encodeURIComponent(foodKey)}${scopeSearch(scope)}` : null;

  // Initial query for SearchBar — pre-fills the input from the URL on page load
  const initialQuery = searchParams.get('q') ?? '';

  const fromFoodState = (r) => (matchesFood(r, foodKey) ? { fromFood: foodKey } : undefined);

  const renderCard = (r) => (
    <div key={r.id} id={`card-${r.id}`}>
      <RestaurantCard
        restaurant={r}
        isActive={selectedId === r.id}
        onClick={() => setSelectedId(r.id)}
        distanceKm={distanceFromUserKm(userLocation, isFallback, r)}
        linkState={fromFoodState(r)}
      >
        <FoodPills foodKeys={r.foodKeys} foods={foods} selectedKey={foodKey} className="mt-1.5" />
      </RestaurantCard>
    </div>
  );

  const errorNotice = error && !isLoading && (
    <RetryNotice title="Không tải được danh sách quán" error={error} onRetry={retry} className="p-3" />
  );

  return (
    // pb-bottom-nav keeps the map above the fixed mobile bottom navigation;
    // dvh: the page ends where the phone browser's own toolbar begins
    <div className="flex h-screen supports-[height:100dvh]:h-dvh flex-col overflow-hidden pb-bottom-nav pt-[env(safe-area-inset-top)]">
      <FoodGlyphSprite />

      {/* ── Navbar — shared header, with the search wired to map state ───── */}
      <AppHeader
        fluid
        search={
          <SearchBar
            inputId={SEARCH_INPUT_ID}
            placeholder={SEARCH_PLACEHOLDER}
            onDishSelect={handleDishSelect}
            onSearch={handleSearch}
            onClear={handleClearSearch}
            initialQuery={initialQuery}
          />
        }
      />

      {/* ── Body: sidebar + map ──────────────────────────────────────────── */}
      <main
        id="main"
        data-map-layout={isMobile ? `mobile-${mobileView}` : 'desktop'}
        className="relative flex min-h-0 flex-1 overflow-hidden"
        style={panelView ? { '--fm-map-panel-h': `${panelHeight}px` } : undefined}
      >

        {/* Sidebar — on phones the bottom panel, or the list */}
        <aside
          ref={asideRef}
          data-map-panel
          aria-label={isMobile ? (listView ? 'Danh sách quán' : 'Bảng điều khiển bản đồ') : undefined}
          className={!isMobile ? ASIDE_DESKTOP : listView ? ASIDE_MOBILE_LIST : ASIDE_MOBILE_MAP}
        >

          {activeDish && (
            <ActiveDishFilter food={activeDish} onClear={handleClearDish} />
          )}

          {/* Mode — "Quán" / "Món" (and, on the phone map, the list switch) */}
          <div className={`border-b border-line bg-surface px-3 py-2 ${isMobile ? 'flex items-center justify-between gap-2' : ''}`}>
            <ScopeBar value={mode} onChange={handleModeChange} options={MODE_OPTIONS} legend="Xem bản đồ theo" />
            {panelView && <ViewToggle toList onClick={handleViewToggle} />}
          </div>

          {mode === 'food' && (
            <MapFoodRail
              status={catalogState.status}
              foods={railFoods}
              counts={foodCounts}
              selectedKey={foodKey}
              onSelect={handleFoodSelect}
              error={catalogState.error}
              onRetry={catalogState.retry}
            />
          )}

          {/* Phone map: the selected restaurant's card takes the header's place */}
          {panelView && selectedRestaurant ? (
            <section data-map-preview aria-label="Quán đang chọn" className="border-b border-line bg-surface">
              <MapPopupCard
                variant="sheet"
                restaurant={selectedRestaurant}
                distanceKm={distanceFromUserKm(userLocation, isFallback, selectedRestaurant)}
                foods={foods}
                foodKey={foodKey}
                detailLink={{ to: `/restaurants/${selectedRestaurant.id}`, state: fromFoodState(selectedRestaurant) }}
                onClose={handleDeselect}
              />
            </section>
          ) : selectedFood ? (
            <div data-food-header className="border-b border-line bg-surface px-4 py-3">
              <div className="flex items-center gap-3">
                <FoodArt
                  artKey={selectedFood.artKey}
                  parentCategory={selectedFood.parentCategory}
                  tone={selectedFood.tone}
                  className="h-10 w-10 flex-shrink-0 rounded-full border border-line"
                />
                <div className="min-w-0 flex-1">
                  <h2 className="truncate text-body font-bold text-ink">{selectedFood.label}</h2>
                  <p data-food-count className="text-small text-ink-muted">{foodCount}</p>
                </div>
              </div>
              <div className="mt-1 flex flex-wrap items-center gap-x-4">
                <Link
                  to={foodPageHref}
                  className="inline-flex min-h-11 items-center gap-1 text-small font-semibold text-brand-700
                             hover:text-brand-800 hover:underline"
                >
                  Xem trang món {selectedFood.label}
                </Link>
                <button
                  type="button"
                  onClick={handleClearFood}
                  className="inline-flex min-h-11 items-center text-small font-semibold text-ink-muted
                             hover:text-ink hover:underline"
                >
                  Bỏ chọn món
                </button>
                {/* the phone map has no list below: the empty state's action lives here */}
                {panelView && matches.length === 0 && !isLoading && !error && (
                  <button
                    type="button"
                    onClick={handleZoomOut}
                    className="inline-flex min-h-11 items-center text-small font-semibold text-brand-700
                               hover:text-brand-800 hover:underline"
                  >
                    Thu nhỏ bản đồ
                  </button>
                )}
              </div>
            </div>
          ) : (
            <div className="border-b border-line bg-surface px-4 py-3">
              <div className="flex items-center gap-2">
                <span
                  aria-hidden="true"
                  className={`h-2 w-2 flex-shrink-0 rounded-full ${
                    isLoading ? 'bg-warning animate-pulse' :
                    error     ? 'bg-danger' :
                                'bg-success'
                  }`}
                />
                {sidebarLabel && (
                  <p className="truncate text-small font-semibold text-ink-muted">{sidebarLabel}</p>
                )}
              </div>
              {mode === 'food' && catalogState.status === 'ready' && (
                <p className="mt-1 text-small text-ink-muted">Chọn một món để làm nổi bật quán có món đó.</p>
              )}
            </div>
          )}

          {/* Settled count for screen readers (announced once per settle) */}
          <p className="sr-only" aria-live="polite" data-map-announcer>{announced}</p>

          {/* Phone map: the one error message, in the panel */}
          {panelView && errorNotice && <div className="px-3 pt-3">{errorNotice}</div>}

          {!panelView && (
            <>
              {/* Sort selector — only when we have results to sort */}
              {count > 0 && (
                <div role="group" aria-label="Sắp xếp" className="flex items-center gap-1 border-b border-line bg-surface px-3 py-2">
                  {(mode === 'food' ? FOOD_SORT_OPTIONS : SORT_OPTIONS).map((opt) => (
                    <button
                      key={opt.value}
                      type="button"
                      title={opt.title}
                      aria-pressed={effectiveSort === opt.value}
                      onClick={() => setSortMode(opt.value)}
                      className={`flex-1 whitespace-nowrap rounded-full px-2 py-1.5 text-label transition-colors duration-fast
                                  ${isMobile ? 'min-h-11' : ''}
                                  ${effectiveSort === opt.value
                                    ? 'bg-brand-100 text-brand-800'
                                    : 'font-normal text-ink-muted hover:bg-surface-sunken hover:text-ink'}`}
                    >
                      {opt.label}
                    </button>
                  ))}
                </div>
              )}

              {/* Restaurant list — dimmed while a fresh viewport fetch is in flight */}
              <div
                ref={listRef}
                data-map-list
                className={`flex-1 overflow-y-auto p-3 space-y-2 transition-opacity duration-300
                            ${listView ? 'pb-24' : ''}
                            ${isLoading && count > 0 ? 'opacity-50' : 'opacity-100'}`}
              >
                {/* The one error message; the previous restaurants stay below it */}
                {errorNotice}

                {isLoading && count === 0 ? (
                  Array.from({ length: 5 }, (_, i) => <RestaurantCardSkeleton key={i} />)
                ) : selectedFood ? (
                  <>
                    {matches.length > 0 ? (
                      <section aria-labelledby="map-food-matches" className="space-y-2">
                        <h3 id="map-food-matches" className="px-1 pt-1 text-small font-bold text-ink">
                          Có {selectedFood.label} ({matches.length})
                        </h3>
                        {matches.map(renderCard)}
                      </section>
                    ) : !error && (
                      <EmptyMessage
                        icon="utensils"
                        title={`Khung bản đồ này chưa có quán ${selectedFood.label} nào FoodMap biết.`}
                        actions={[
                          { label: 'Thu nhỏ bản đồ', onClick: handleZoomOut },
                          { label: `Xem trang món ${selectedFood.label}`, to: foodPageHref },
                        ]}
                      />
                    )}
                    {others.length > 0 && (
                      <details data-other-restaurants className="group pt-1">
                        <summary
                          className="flex min-h-11 cursor-pointer list-none items-center gap-2 rounded-field px-1
                                     text-small font-bold text-ink-muted hover:text-ink
                                     focus-visible:outline focus-visible:outline-2 focus-visible:outline-brand-700
                                     [&::-webkit-details-marker]:hidden"
                        >
                          <Icon name="chevron-left" size={16} strokeWidth={2.5}
                                className="rotate-180 transition-transform duration-fast group-open:-rotate-90" />
                          Quán khác trong khung ({others.length})
                        </summary>
                        <div className="mt-1 space-y-2">{others.map(renderCard)}</div>
                      </details>
                    )}
                  </>
                ) : count === 0 ? (
                  !error && (
                    <EmptyState
                      isDishFilter={!!activeDish}
                      isSearchMode={!!searchQuery}
                      searchQuery={searchQuery}
                      // Cold-region signal — the backend just enqueued cells for
                      // this area but the local DB is still empty. Tell the user
                      // to come back rather than implying the area is dead.
                      isIndexing={indexingCells > 0 && !searchQuery && !activeDish}
                      onClearFilter={handleClearDish}
                      onClearSearch={handleClearSearch}
                    />
                  )
                ) : (
                  rankedRestaurants.map(renderCard)
                )}
              </div>
            </>
          )}

          {/* Phone list: back to the map, within thumb reach above the bottom nav */}
          {listView && (
            <div className="pointer-events-none absolute inset-x-0 bottom-8 flex justify-center">
              <ViewToggle onClick={handleViewToggle} className="pointer-events-auto shadow-e3" />
            </div>
          )}
        </aside>

        {/* Map */}
        <div ref={mapAreaRef} aria-hidden={listView || undefined} className="relative min-w-0 flex-1">
          <MapView
            ref={mapViewRef}
            restaurants={rankedRestaurants}
            selectedId={selectedId}
            center={userLocation}
            userLocationIsReal={hasRealLocation}
            isLoading={isLoading}
            fitOnUpdate={!!(searchQuery || activeDish)}
            foodKey={foodKey}
            foods={foods}
            initialView={restored?.view ?? null}
            popups={!isMobile}
            selectionOffset={selectionOffset}
            onMarkerClick={handleMarkerClick}
            onDeselect={handleDeselect}
            onViewportChange={handleViewportChange}
          />

          {indexingVisible && (
            <div
              className="pointer-events-none absolute inset-x-0 bottom-4 flex justify-center z-10"
              style={panelView ? { bottom: panelHeight + 16 } : undefined}
            >
              <div className="flex items-center gap-2 rounded-full bg-ink/90 px-4 py-1.5 text-white
                              shadow-e2 backdrop-blur-sm">
                <span aria-hidden="true" className="h-2 w-2 rounded-full bg-warning animate-pulse" />
                <span className="text-label">
                  Đang cập nhật khu vực này — quay lại sau ít phút để thấy thêm quán
                </span>
              </div>
            </div>
          )}
        </div>
      </main>

      <MobileBottomNav />
    </div>
  );
}

// ── Sub-components ────────────────────────────────────────────────────────────

/** The phone's "Danh sách" / "Bản đồ" switch (names the view it opens). */
function ViewToggle({ toList = false, onClick, className = '' }) {
  return (
    <button
      type="button"
      data-view-toggle={toList ? 'list' : 'map'}
      onClick={onClick}
      className={`inline-flex min-h-11 flex-shrink-0 items-center gap-1.5 rounded-full border-2 border-ink bg-surface px-4
                  text-small font-semibold text-ink transition-colors duration-fast hover:bg-surface-sunken
                  focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-2
                  focus-visible:outline-brand-700 ${className}`}
    >
      <Icon name={toList ? 'utensils' : 'map'} size={16} strokeWidth={2.25} />
      {toList ? 'Danh sách' : 'Bản đồ'}
    </button>
  );
}

function EmptyState({ isDishFilter, isSearchMode, isIndexing, searchQuery, onClearFilter, onClearSearch }) {
  if (isSearchMode) {
    return (
      <EmptyMessage
        icon="search"
        title={<>Không tìm thấy quán nào cho <span className="text-ink">“{searchQuery}”</span></>}
        body="Thử từ khoá khác, hoặc xoá tìm kiếm để xem quán trên bản đồ."
        actions={[{ label: 'Xoá tìm kiếm', onClick: onClearSearch }]}
      />
    );
  }

  if (isDishFilter) {
    return (
      <EmptyMessage
        icon="utensils"
        title="Chưa có quán nào có món này"
        actions={[{ label: 'Bỏ lọc món', onClick: onClearFilter }]}
      />
    );
  }

  // Cold-region: backend reports it just enqueued cells, but our DB has
  // nothing yet. Distinguish this from "this area is genuinely empty" so
  // the user understands data will arrive shortly.
  if (isIndexing) {
    return (
      <EmptyMessage
        icon="clock"
        tone="warning"
        pulse
        title="Đang cập nhật khu vực này…"
        body="FoodMap đang tìm quán quanh đây — quay lại sau ít phút nhé."
      />
    );
  }

  return (
    <EmptyMessage
      icon="map-pin"
      title="Khu vực này chưa có quán nào"
      body="Thử kéo hoặc phóng to, thu nhỏ bản đồ để khám phá."
    />
  );
}

const EMPTY_TONES = {
  neutral: 'bg-surface-sunken text-ink-muted',
  danger:  'bg-danger-tint text-danger-ink',
  warning: 'bg-warning-tint text-warning-ink',
};

const EMPTY_ACTION_CLASS = `inline-flex min-h-11 items-center rounded-full px-3 text-small font-semibold text-brand-700
                            transition-colors hover:bg-brand-50 hover:text-brand-800`;

function EmptyMessage({ icon, tone = 'neutral', pulse = false, title, body, actions = [] }) {
  return (
    <div className="flex flex-col items-center justify-center gap-3 px-4 py-16 text-center">
      <span className={`flex h-12 w-12 items-center justify-center rounded-full ${EMPTY_TONES[tone]}`}>
        <Icon name={icon} size={24} className={pulse ? 'animate-pulse' : ''} />
      </span>
      <p className="text-body font-semibold text-ink-muted">{title}</p>
      {body && <p className="text-small text-ink-muted">{body}</p>}
      {actions.length > 0 && (
        <div className="flex flex-wrap justify-center gap-1">
          {actions.map((action) => (action.to ? (
            <Link key={action.label} to={action.to} className={EMPTY_ACTION_CLASS}>{action.label}</Link>
          ) : (
            <button key={action.label} type="button" onClick={action.onClick} className={EMPTY_ACTION_CLASS}>
              {action.label}
            </button>
          )))}
        </div>
      )}
    </div>
  );
}
