/**
 * Restaurant ranking utilities for the discovery sidebar.
 *
 * All functions here are pure — they take the raw restaurant list plus a
 * geographic anchor (map centre) and return an annotated, sorted copy.
 * Memoise the call site (useMemo) — these run on every viewport / sort change.
 */

const EARTH_RADIUS_KM = 6371;
const DEG_TO_RAD       = Math.PI / 180;

/**
 * Great-circle distance between two {lat,lng} points, in kilometres.
 * Returns Infinity if either point is null/undefined so unranked items
 * sort to the bottom.
 */
export function haversineKm(a, b) {
  if (!a || !b) return Infinity;
  if (a.lat == null || a.lng == null || b.lat == null || b.lng == null) return Infinity;

  const dLat = (b.lat - a.lat) * DEG_TO_RAD;
  const dLng = (b.lng - a.lng) * DEG_TO_RAD;
  const lat1 = a.lat * DEG_TO_RAD;
  const lat2 = b.lat * DEG_TO_RAD;

  const h = Math.sin(dLat / 2) ** 2
          + Math.cos(lat1) * Math.cos(lat2) * Math.sin(dLng / 2) ** 2;
  return 2 * EARTH_RADIUS_KM * Math.asin(Math.sqrt(h));
}

/**
 * Compose rating (0..5) and popularity (log-scaled review count) into a
 * single 0..1 quality score. Used to "discount" distance in best-match mode.
 */
function qualityScore(r) {
  const rating     = r.avgRating ?? 0;                       // 0..5
  const popularity = Math.log10((r.reviewCount ?? 0) + 1);   // 0..~3
  return (rating / 5) * 0.6 + Math.min(popularity / 3, 1) * 0.4;
}

/**
 * Bayesian-weighted rating. Smooths low-vote outliers (a single 5★ review
 * shouldn't beat a 4.6★ restaurant with 200 reviews).
 *   C = global prior mean rating
 *   m = vote-count threshold at which the prior fades out
 */
function bayesianRating(r) {
  const C = 3.5;
  const m = 10;
  const v = r.reviewCount ?? 0;
  const R = r.avgRating ?? 0;
  return (v / (v + m)) * R + (m / (v + m)) * C;
}

/**
 * Format a distance value for display, Vietnamese style.
 *   <10 m → "dưới 10 m" (never round 0–9 m up to a claim of "10 m")
 *   <1km  → "320 m"     (rounded to 10 m — finer precision is false precision)
 *   ≥1km  → "1,4 km"
 */
export function formatDistance(km) {
  if (km == null || !Number.isFinite(km) || km < 0) return null;
  const metres = km * 1000;
  if (metres < 10) return 'dưới 10 m';
  const rounded = Math.round(metres / 10) * 10;
  if (rounded < 1000) return `${rounded} m`;
  return `${km.toLocaleString('vi-VN', { minimumFractionDigits: 1, maximumFractionDigits: 1 })} km`;
}

/**
 * Distance from the *user* to a restaurant, or null when we don't actually
 * know where the user is (no location yet, or the city-centre fallback).
 * Use this — not the map-centre `distanceKm` used for sorting — for anything
 * shown to the user as "how far away".
 */
export function distanceFromUserKm(userLocation, isFallback, restaurant) {
  if (!userLocation || isFallback) return null;
  if (restaurant?.latitude == null || restaurant?.longitude == null) return null;
  return haversineKm(userLocation, { lat: restaurant.latitude, lng: restaurant.longitude });
}

/**
 * Annotate every restaurant with a `distanceKm` field and return a sorted
 * copy according to the given mode.
 *
 *   'best'      composite: distance penalised, quality discounts up to 1.5km
 *   'closest'   pure haversine ascending
 *   'top_rated' Bayesian-weighted rating descending
 *
 * In best-match mode the top 3 results also receive `isFeatured: true`,
 * which the card renders as a "Nearby pick" badge.
 */
export function rankRestaurants(restaurants, center, mode = 'best') {
  if (!Array.isArray(restaurants) || restaurants.length === 0) return [];

  // Single pass: clone + annotate with distance
  const annotated = restaurants.map((r) => {
    const distanceKm = center && r.latitude != null && r.longitude != null
      ? haversineKm(center, { lat: r.latitude, lng: r.longitude })
      : null;
    return { ...r, distanceKm };
  });

  const sorted = annotated.sort((a, b) => {
    if (mode === 'closest') {
      return (a.distanceKm ?? Infinity) - (b.distanceKm ?? Infinity);
    }
    if (mode === 'top_rated') {
      return bayesianRating(b) - bayesianRating(a);
    }
    // default: 'best'
    const scoreA = (a.distanceKm ?? Infinity) - qualityScore(a) * 1.5;
    const scoreB = (b.distanceKm ?? Infinity) - qualityScore(b) * 1.5;
    return scoreA - scoreB;
  });

  if (mode === 'best') {
    return sorted.map((r, i) => (i < 3 ? { ...r, isFeatured: true } : r));
  }
  return sorted;
}
