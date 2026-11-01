import { forwardRef, useEffect, useImperativeHandle, useRef } from 'react';
import { createRoot } from 'react-dom/client';
import { useNavigate } from 'react-router-dom';
import goongjs from '@goongmaps/goong-js';
import Spinner from '../ui/Spinner';
import { MapHoverCard, MapPopupCard } from './MapPopupCard';
import { glyphHref, toneColor } from './FoodGlyphSprite';
import { distanceFromUserKm } from '../../utils/ranking';
import { matchesFood, pinAppearance } from '../../utils/mapFood';

const MAP_KEY      = import.meta.env.VITE_GOONG_MAP_KEY ?? '';
const HCMC_CENTER  = [106.7009, 10.7769];
const DEFAULT_ZOOM = 14;

// Radial popup offsets (px from the pin centre): pin radius (18) + a gap.
// The selected pin has a 3px ring, so its popup sits a little further out.
const HOVER_POPUP_OFFSET    = 24;
const SELECTED_POPUP_OFFSET = 28;
// Pan target for a selected pin: below centre, so its popup above has room
const DEFAULT_SELECTION_OFFSET = [0, 80];

// Pin state → its modifier class (see .restaurant-pin in index.css).
const PIN_CLASSES = {
  food:  'restaurant-pin--food',
  match: 'restaurant-pin--match',
  dim:   'restaurant-pin--dim',
};

const prefersReducedMotion = () =>
  typeof window !== 'undefined' && window.matchMedia?.('(prefers-reduced-motion: reduce)').matches === true;

/**
 * MapView — Goong GL map with fully interactive restaurant markers.
 *
 * Props
 * ─────
 * restaurants        { id, name, address, latitude, longitude, avgRating,
 *                      reviewCount, priceLevel, cuisineType, photos, foodKeys } []
 * selectedId         id of the currently selected restaurant (syncs map ↔ sidebar)
 * center             { lat, lng } — flies to this point on geolocation resolve
 * userLocationIsReal true only when `center` is the user's real position (not
 *                    the city-centre fallback). Controls the "you are here"
 *                    dot and whether popups show a distance.
 * isLoading          shows an unobtrusive loading pill while viewport data is in-flight
 * fitOnUpdate        when true, fitBounds to the result set each time restaurants changes
 *                    (use this in search / dish-filter mode, not viewport mode)
 * foodKey            the food chosen in "Món" mode, or null: pins of restaurants
 *                    the server classified with it are emphasised, the others
 *                    shrink to dots
 * foods              the catalog's foods as a Map by key (glyph art + tone), or null
 * initialView        { center: { lng, lat }, zoom } — start here instead of the
 *                    city centre, and never fly afterwards (Back to the map)
 * popups             false on the phone layout: no Goong popups (hover or
 *                    selected) — the page shows its own bottom card instead
 * selectionOffset    [x, y] px for the pan to a selected pin (default [0, 80]:
 *                    below centre, under its popup)
 * onMarkerClick      (restaurant) => void — map pin was clicked
 * onDeselect         () => void           — user clicked empty map canvas
 * onViewportChange   ({ swLat, swLng, neLat, neLng }, { center, zoom }) => void
 *
 * ref                { zoomOut() }
 *
 * Interaction model
 * ─────────────────
 * Hover marker  → lightweight name + facts preview
 * Click marker  → selected state, rich popup, map pans to pin
 * Click canvas  → clears selection, closes popup
 * selectedId ↑  → map pans to that marker and opens popup (sidebar → map sync)
 * foodKey ↑     → pins change class in place; no marker is recreated, the map
 *                 doesn't move
 */
const MapView = forwardRef(function MapView({
  restaurants = [],
  selectedId,
  center,
  userLocationIsReal = false,
  isLoading    = false,
  fitOnUpdate  = false,
  foodKey      = null,
  foods        = null,
  initialView  = null,
  popups       = true,
  selectionOffset = DEFAULT_SELECTION_OFFSET,
  onMarkerClick,
  onDeselect,
  onViewportChange,
}, ref) {
  const navigate = useNavigate();

  const containerRef     = useRef(null);
  const mapRef           = useRef(null);
  // restaurantId → { marker, el, glyph, restaurant }
  const markersRef       = useRef(new Map());
  const userMarkerRef    = useRef(null);
  const hoverPopupRef    = useRef(null);   // ephemeral hover preview
  const selectedPopupRef = useRef(null);   // { popup, root, restaurant, close } while a marker is selected

  // A restored view is the user's own: never fly away from it, and don't pan
  // to the restored selection either.
  const restoredRef          = useRef(Boolean(initialView));
  const restoredSelectionRef = useRef(initialView ? selectedId ?? null : undefined);

  // Callback refs — hold the latest prop without re-registering map listeners
  const onMarkerClickRef    = useRef(onMarkerClick);
  const onDeselectRef       = useRef(onDeselect);
  const onViewportChangeRef = useRef(onViewportChange);
  const navigateRef         = useRef(navigate);
  const fitOnUpdateRef      = useRef(fitOnUpdate);
  const selectedIdRef       = useRef(selectedId);
  const centerRef           = useRef(center);
  const locationIsRealRef   = useRef(userLocationIsReal);
  const foodKeyRef          = useRef(foodKey);
  const foodsRef            = useRef(foods);
  const popupsRef           = useRef(popups);
  const selectionOffsetRef  = useRef(selectionOffset);

  onMarkerClickRef.current    = onMarkerClick;
  onDeselectRef.current       = onDeselect;
  onViewportChangeRef.current = onViewportChange;
  navigateRef.current         = navigate;
  fitOnUpdateRef.current      = fitOnUpdate;
  selectedIdRef.current       = selectedId;
  centerRef.current           = center;
  locationIsRealRef.current   = userLocationIsReal;
  foodKeyRef.current          = foodKey;
  foodsRef.current            = foods;
  popupsRef.current           = popups;
  selectionOffsetRef.current  = selectionOffset;

  useImperativeHandle(ref, () => ({
    zoomOut: () => mapRef.current?.zoomOut({ duration: prefersReducedMotion() ? 0 : 300 }),
  }), []);

  // Distance shown in popups — only from a real user location
  const userDistanceKm = (restaurant) =>
    distanceFromUserKm(centerRef.current, !locationIsRealRef.current, restaurant);

  /**
   * One pin's look from the current food and selection. Only our own classes
   * and the glyph reference change — assigning className would strip Goong's
   * `mapboxgl-marker` classes, which carry the absolute positioning.
   */
  const applyPin = (entry) => {
    const { el, glyph, restaurant } = entry;
    const selected = restaurant.id === selectedIdRef.current;
    const { state, glyphKey } = pinAppearance(restaurant, { foodKey: foodKeyRef.current, selected });
    Object.entries(PIN_CLASSES).forEach(([name, cls]) => el.classList.toggle(cls, name === state));
    el.classList.toggle('restaurant-pin--active', selected);
    el.dataset.pinState = state;
    if (glyphKey) {
      const food = foodsRef.current?.get(glyphKey);
      glyph.querySelector('use').setAttribute('href', glyphHref(food?.artKey ?? glyphKey));
      glyph.style.color = toneColor(food?.tone) ?? '';
    }
  };
  const applyAllPins = () => markersRef.current.forEach(applyPin);

  /** The selected popup's content for the current food. */
  const popupCard = (restaurant, onClose) => {
    const key = foodKeyRef.current;
    const linkState = matchesFood(restaurant, key) ? { fromFood: key } : undefined;
    return (
      <MapPopupCard
        restaurant={restaurant}
        distanceKm={userDistanceKm(restaurant)}
        foods={foodsRef.current}
        foodKey={key}
        onNavigate={() => navigateRef.current?.(`/restaurants/${restaurant.id}`, { state: linkState })}
        onClose={onClose}
      />
    );
  };

  // ── Initialise map (runs once) ─────────────────────────────────────────────
  useEffect(() => {
    if (!containerRef.current || mapRef.current) return;

    goongjs.accessToken = MAP_KEY;

    const map = new goongjs.Map({
      container: containerRef.current,
      style:     'https://tiles.goong.io/assets/goong_map_web.json',
      center:    initialView ? [initialView.center.lng, initialView.center.lat] : HCMC_CENTER,
      zoom:      initialView ? initialView.zoom : DEFAULT_ZOOM,
    });

    map.addControl(new goongjs.NavigationControl(),  'top-right');
    map.addControl(new goongjs.ScaleControl(),        'bottom-right');
    map.addControl(
      new goongjs.GeolocateControl({
        positionOptions:   { enableHighAccuracy: true },
        trackUserLocation: false,
      }),
      'top-right',
    );

    // Emit viewport bounds (and the camera, for Back) after every pan/zoom + on initial load
    const emitViewport = () => {
      const b = map.getBounds();
      const c = map.getCenter();
      onViewportChangeRef.current?.(
        {
          swLat: b.getSouth(), swLng: b.getWest(),
          neLat: b.getNorth(), neLng: b.getEast(),
        },
        { center: { lng: c.lng, lat: c.lat }, zoom: map.getZoom() },
      );
    };
    map.on('load',    emitViewport);
    map.on('moveend', emitViewport);

    // Canvas click → deselect (marker clicks stop propagation so they won't reach here)
    map.on('click', () => {
      onDeselectRef.current?.();
    });

    // Goong resizes itself on window 'resize' only — before the page has
    // switched between its phone and desktop layouts (a rotation). Follow
    // the container itself; only a real size change resizes (the phone's
    // bottom panel and list cover the map without changing its size).
    let size = null;
    const resizeObserver = typeof ResizeObserver === 'undefined' ? null : new ResizeObserver(([entry]) => {
      const { width, height } = entry?.contentRect ?? {};
      if (size && (width !== size.width || height !== size.height)) map.resize();
      size = { width, height };
    });
    resizeObserver?.observe(containerRef.current);

    mapRef.current = map;
    return () => {
      resizeObserver?.disconnect();
      // Close popups first so their React roots unmount
      hoverPopupRef.current?.remove();
      selectedPopupRef.current?.popup.remove();
      selectedPopupRef.current = null;
      // Markers belong to this map instance — forget them, so a remount
      // (StrictMode / Fast Refresh) re-creates them on the new map.
      markersRef.current.forEach(({ marker }) => marker.remove());
      markersRef.current.clear();
      userMarkerRef.current?.remove();
      userMarkerRef.current = null;
      map.remove();
      mapRef.current = null;
    };
  }, []); // eslint-disable-line react-hooks/exhaustive-deps

  // ── Fly to user location when geolocation resolves ────────────────────────
  useEffect(() => {
    const map = mapRef.current;
    if (!map || !center) return;

    if (!restoredRef.current) {
      map.flyTo({ center: [center.lng, center.lat], zoom: DEFAULT_ZOOM, duration: prefersReducedMotion() ? 0 : 1200 });
    }

    // The "you are here" dot only marks a real position — never the
    // city-centre fallback used when location permission is denied.
    if (!userLocationIsReal) {
      userMarkerRef.current?.remove();
      userMarkerRef.current = null;
      return;
    }
    if (userMarkerRef.current) {
      userMarkerRef.current.setLngLat([center.lng, center.lat]);
      return;
    }
    const el = document.createElement('div');
    el.className = 'user-location-dot';
    el.setAttribute('aria-label', 'Vị trí của bạn');
    userMarkerRef.current = new goongjs.Marker({ element: el })
      .setLngLat([center.lng, center.lat])
      .addTo(map);
  }, [center?.lat, center?.lng, userLocationIsReal]); // eslint-disable-line react-hooks/exhaustive-deps

  // ── Reconcile restaurant markers ──────────────────────────────────────────
  useEffect(() => {
    const map = mapRef.current;
    if (!map) return;

    const nextIds = new Set(restaurants.map((r) => r.id));

    // Remove stale markers
    markersRef.current.forEach(({ marker }, id) => {
      if (!nextIds.has(id)) {
        marker.remove();
        markersRef.current.delete(id);
      }
    });

    // Add new markers; existing ones keep their element and take the newest data
    restaurants.forEach((restaurant) => {
      const existing = markersRef.current.get(restaurant.id);
      if (existing) {
        existing.restaurant = restaurant;
        applyPin(existing);
        return;
      }

      const el = document.createElement('div');
      el.className = 'restaurant-pin';
      el.setAttribute('aria-label', restaurant.name);
      el.dataset.pinId = String(restaurant.id);
      // Any transform belongs on this inner face — Goong owns the root's.
      el.innerHTML = PIN_FACE;
      const glyph = el.querySelector('.restaurant-pin__glyph');
      const current = () => markersRef.current.get(restaurant.id)?.restaurant ?? restaurant;

      // ── Hover preview ────────────────────────────────────────────────────
      el.addEventListener('mouseenter', () => {
        if (!mapRef.current || !popupsRef.current) return;
        const shown = current();
        // Skip hover popup for the currently selected marker
        if (shown.id === selectedIdRef.current) return;

        hoverPopupRef.current?.remove();
        hoverPopupRef.current = reactPopup(
          {
            offset:         HOVER_POPUP_OFFSET,
            closeButton:    false,
            className:      'hover-popup',
            maxWidth:       '240px',
            focusAfterOpen: false,
          },
          <MapHoverCard restaurant={shown} distanceKm={userDistanceKm(shown)} />,
        ).popup
          .setLngLat([shown.longitude, shown.latitude])
          .addTo(mapRef.current);
      });

      el.addEventListener('mouseleave', () => {
        hoverPopupRef.current?.remove();
        hoverPopupRef.current = null;
      });

      // ── Click — tell parent; popup + pan handled by selectedId effect ────
      el.addEventListener('click', (e) => {
        // Stop the click from reaching the map canvas (which would trigger onDeselect)
        e.stopPropagation();
        // Dismiss hover popup immediately for a snappy feel
        hoverPopupRef.current?.remove();
        hoverPopupRef.current = null;
        onMarkerClickRef.current?.(current());
      });

      const marker = new goongjs.Marker({ element: el })
        .setLngLat([restaurant.longitude, restaurant.latitude])
        .addTo(map);

      const entry = { marker, el, glyph, restaurant };
      markersRef.current.set(restaurant.id, entry);
      applyPin(entry);
    });

    // ── fitBounds when in search / dish-filter mode ──────────────────────
    if (fitOnUpdateRef.current && restaurants.length > 0) {
      if (restaurants.length === 1) {
        map.easeTo({
          center:   [restaurants[0].longitude, restaurants[0].latitude],
          zoom:     15,
          duration: 700,
        });
      } else {
        const [first] = restaurants;
        const bounds = restaurants.reduce(
          (b, r) => b.extend([r.longitude, r.latitude]),
          new goongjs.LngLatBounds(
            [first.longitude, first.latitude],
            [first.longitude, first.latitude],
          ),
        );
        map.fitBounds(bounds, {
          // Extra top/bottom/right padding; the map div already excludes the sidebar
          padding:  { top: 60, bottom: 80, left: 40, right: 60 },
          maxZoom:  15,
          duration: 800,
        });
      }
    }
  }, [restaurants]); // eslint-disable-line react-hooks/exhaustive-deps

  // ── Selected marker: update styles, pan map, manage selected popup ────────
  useEffect(() => {
    const map = mapRef.current;
    // The restored selection opens where it is; any later selection pans.
    const skipPan = restoredSelectionRef.current !== undefined && selectedId === restoredSelectionRef.current;
    if (!skipPan) restoredSelectionRef.current = undefined;

    applyAllPins();

    // Close existing selected popup
    if (selectedPopupRef.current) {
      selectedPopupRef.current.popup.remove();
      selectedPopupRef.current = null;
    }

    if (!selectedId || !map) return;

    const entry = markersRef.current.get(selectedId);
    if (!entry) return;

    const { marker, restaurant } = entry;
    const lngLat = marker.getLngLat();

    // Pan map so the pin lands at a comfortable position (slightly below centre
    // so the popup above it has room; positive Y shifts the target down from canvas centre)
    if (!skipPan) {
      map.easeTo({
        center:   [lngLat.lng, lngLat.lat],
        offset:   selectionOffsetRef.current,
        duration: prefersReducedMotion() ? 0 : 500,
      });
    }

    // The phone layout shows its own card for the selection
    if (!popupsRef.current) return;

    // Build the rich selected popup
    const close = () => {
      selectedPopupRef.current?.popup.remove();
      selectedPopupRef.current = null;
      onDeselectRef.current?.();
    };
    const { popup, root } = reactPopup(
      {
        offset:      SELECTED_POPUP_OFFSET,
        closeButton: false,
        maxWidth:    '280px',
      },
      popupCard(restaurant, close),
    );
    popup.setLngLat([lngLat.lng, lngLat.lat]).addTo(map);

    selectedPopupRef.current = { popup, root, restaurant, close };
  }, [selectedId]); // eslint-disable-line react-hooks/exhaustive-deps

  // ── Phone layout: no Goong popups (the page shows a bottom card) ─────────
  useEffect(() => {
    if (popups) return;
    hoverPopupRef.current?.remove();
    hoverPopupRef.current = null;
    selectedPopupRef.current?.popup.remove();
    selectedPopupRef.current = null;
  }, [popups]);

  // ── Food changed: re-class pins in place, refresh the open popup ──────────
  useEffect(() => {
    applyAllPins();
    const open = selectedPopupRef.current;
    if (open) open.root.render(popupCard(open.restaurant, open.close));
  }, [foodKey, foods]); // eslint-disable-line react-hooks/exhaustive-deps

  return (
    <div className="relative w-full h-full">
      <div ref={containerRef} className="map-container" />

      {isLoading && (
        <div className="pointer-events-none absolute inset-x-0 top-3 flex justify-center z-10">
          <div className="flex items-center gap-2 rounded-full border border-line bg-surface/90 px-4 py-1.5
                          shadow-e2 backdrop-blur-sm">
            <Spinner size="sm" />
            <span className="text-label text-ink">Đang tải quán ăn…</span>
          </div>
        </div>
      )}
    </div>
  );
});

export default MapView;

// ── Static helpers ─────────────────────────────────────────────────────────────

/**
 * A Goong popup whose content is a React element, rendered in its own root
 * (popups live outside the app's React tree). The root is unmounted when the
 * popup closes — deferred, so we never unmount during another render.
 */
function reactPopup(options, element) {
  const node = document.createElement('div');
  const root = createRoot(node);
  root.render(element);
  const popup = new goongjs.Popup(options).setDOMContent(node);
  popup.on('close', () => setTimeout(() => root.unmount(), 0));
  return { popup, root };
}

// The pin's inner face: the utensils glyph (same paths as Icon "utensils",
// coloured via currentColor, which .restaurant-pin sets to ink — see index.css
// for the contrast notes) and a food glyph from FoodGlyphSprite. CSS shows one
// of them, or neither (the dimmed dot), from the pin's state class.
const PIN_FACE = `
  <span class="restaurant-pin__face" aria-hidden="true">
    <svg class="restaurant-pin__utensils" xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" fill="none"
         stroke="currentColor" stroke-width="2.25" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
      <path d="M7 3v8M4.5 3v5a2.5 2.5 0 0 0 5 0V3M7 11v10"/>
      <path d="M17 21V3c-2 1.5-3 4-3 7v4h3"/>
    </svg>
    <svg class="restaurant-pin__glyph" xmlns="http://www.w3.org/2000/svg" viewBox="0 0 64 64" aria-hidden="true">
      <use href="#fm-glyph-covered-dish"/>
    </svg>
  </span>`;
