import { isLocation } from './scope';

/**
 * "Chỉ đường" — a Google Maps directions link to a restaurant (Maps URLs,
 * directions action: no API key, nothing is requested by FoodMap; it is a
 * link the user chooses to open).
 *
 * Built from the restaurant's own coordinates and nothing else: the only
 * input is the restaurant, and the only parameters are `api` and
 * `destination`. There is no route origin — the route starts wherever
 * Google Maps / the device places the user, under the user's own permission
 * there. FoodMap's location (browser, scope store) never enters this URL.
 *
 * Plain ESM, no React, so it is unit-testable.
 */
const MAPS_DIRECTIONS = 'https://www.google.com/maps/dir/';

/** True when the restaurant has finite coordinates in range (lat ±90, lng ±180). */
export function hasValidCoordinates(restaurant) {
  return isLocation({ lat: restaurant?.latitude, lng: restaurant?.longitude });
}

/**
 * The directions URL to the restaurant's coordinates, exactly as stored, or
 * null when they are missing or invalid — never a guessed destination.
 */
export function directionsUrl(restaurant) {
  if (!hasValidCoordinates(restaurant)) return null;
  const params = new URLSearchParams({ api: '1', destination: `${restaurant.latitude},${restaurant.longitude}` });
  return `${MAPS_DIRECTIONS}?${params}`;
}
