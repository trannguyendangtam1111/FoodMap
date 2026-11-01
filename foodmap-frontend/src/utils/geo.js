const EARTH_RADIUS_KM = 6371;

/** Haversine distance in metres between two lat/lng points. */
export function distanceMetres(lat1, lng1, lat2, lng2) {
  const toRad = (deg) => (deg * Math.PI) / 180;
  const dLat = toRad(lat2 - lat1);
  const dLng = toRad(lng2 - lng1);
  const a =
    Math.sin(dLat / 2) ** 2 +
    Math.cos(toRad(lat1)) * Math.cos(toRad(lat2)) * Math.sin(dLng / 2) ** 2;
  return 2 * EARTH_RADIUS_KM * Math.asin(Math.sqrt(a)) * 1000;
}

/** Format metres as a readable distance string. */
export function formatDistance(metres) {
  if (metres == null) return '';
  if (metres < 1000) return `${Math.round(metres)} m`;
  return `${(metres / 1000).toFixed(1)} km`;
}

/**
 * Compute a bounding box around a lat/lng point for a given radius in metres.
 * Returns { swLat, swLng, neLat, neLng }.
 */
export function boundingBox(lat, lng, radiusMetres) {
  const latDelta = (radiusMetres / 1000 / EARTH_RADIUS_KM) * (180 / Math.PI);
  const lngDelta = latDelta / Math.cos((lat * Math.PI) / 180);
  return {
    swLat: lat - latDelta,
    swLng: lng - lngDelta,
    neLat: lat + latDelta,
    neLng: lng + lngDelta,
  };
}
