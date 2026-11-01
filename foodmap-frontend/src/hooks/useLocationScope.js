import { useCallback, useSyncExternalStore } from 'react';
import { requestUserLocation } from '../utils/location';
import { CITY_CENTRE_SCOPE, MODES, isLocation } from '../utils/scope';

/**
 * This tab's location scope — a module store shared by "Thèm gì?" and the
 * cà mèn and the food page. Memory only: it survives in-app navigation
 * but not a reload, and nothing is written to localStorage or a URL.
 *
 *   scope     { mode: CITY_CENTER } | { mode: USER, location } | { mode: AREA, areaKey, areaLabel }
 *   location  the last location the user granted in this tab, or null
 *   chosen    whether the user picked the scope themselves
 *
 * Location is requested ONLY through chooseNearMe(), which callers invoke
 * from an explicit user action ("Gần tôi"). Nothing here runs on load.
 */
const initialState = () => ({ scope: CITY_CENTRE_SCOPE, location: null, chosen: false });
let state = initialState();
const listeners = new Set();

function setState(next) {
  state = { ...state, ...next };
  listeners.forEach((listener) => listener());
}

const subscribe = (listener) => {
  listeners.add(listener);
  return () => listeners.delete(listener);
};
const getSnapshot = () => state;

export function selectCityCentre({ chosen = true } = {}) {
  setState({ scope: CITY_CENTRE_SCOPE, chosen: chosen || state.chosen });
}

export function selectArea(areaKey, areaLabel) {
  setState({ scope: { mode: MODES.AREA, areaKey, areaLabel }, chosen: true });
}

/**
 * A location obtained elsewhere in this tab (the cà mèn asks on "Mở cà mèn").
 * It becomes the scope only while the user hasn't chosen one.
 */
export function rememberLocation(location) {
  if (!isLocation(location)) return;
  const next = { location: { lat: location.lat, lng: location.lng } };
  if (!state.chosen) next.scope = { mode: MODES.USER, location: next.location };
  setState(next);
}

/**
 * "Gần tôi": uses the location already granted in this tab, otherwise asks
 * the browser once for this press. Resolves true when the scope is now USER;
 * false when no location could be had (denied / unavailable / slow) — the
 * scope is then left exactly as it was.
 */
export async function chooseNearMe({ locate = requestUserLocation, signal } = {}) {
  let location = state.location;
  if (!location) {
    location = await locate({ signal });
    if (signal?.aborted) return false;
  }
  if (!isLocation(location)) return false;
  setState({ location: { lat: location.lat, lng: location.lng }, scope: { mode: MODES.USER, location }, chosen: true });
  return true;
}

/** The current scope state, for callers outside React render (e.g. the cà mèn's prepare). */
export function getLocationState() {
  return state;
}

/** Tests only. */
export function resetLocationScope() {
  state = initialState();
  listeners.forEach((listener) => listener());
}

export default function useLocationScope() {
  const snapshot = useSyncExternalStore(subscribe, getSnapshot, getSnapshot);
  return {
    scope: snapshot.scope,
    // The location granted in this tab — for API requests only, never a URL.
    location: snapshot.location,
    hasLocation: snapshot.location != null,
    selectCityCentre: useCallback(() => selectCityCentre(), []),
    // The server refused the scope (400): back to the city centre, which the
    // caller announces; not counted as the user's own choice.
    fallBackToCityCentre: useCallback(() => selectCityCentre({ chosen: false }), []),
    selectArea: useCallback((key, label) => selectArea(key, label), []),
    chooseNearMe: useCallback((options) => chooseNearMe(options), []),
  };
}
