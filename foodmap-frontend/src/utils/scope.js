/**
 * Where FoodMap looks — the client side of the server's LocationScope.
 *
 *   { mode: 'CITY_CENTER' }                       no location sent
 *   { mode: 'USER', location: { lat, lng } }      the user's own location, this tab only
 *   { mode: 'AREA', areaKey, areaLabel? }         a FoodMap discovery area (server-owned)
 *
 * toScopeParams is THE ONLY scope → request-params mapping. Area centres
 * never exist on the client; a radius is never sent (the server's default and
 * ladder apply). Coordinates go to API requests only — never into a page URL
 * (see scopeSearch) or any storage.
 *
 * Plain ESM, no React, so it is unit-testable.
 */

export const MODES = Object.freeze({ USER: 'USER', AREA: 'AREA', CITY_CENTER: 'CITY_CENTER' });

export const CITY_CENTRE_SCOPE = Object.freeze({ mode: MODES.CITY_CENTER });

/** Request params for /decisions/* — USER → lat, lng; AREA → area; CITY_CENTER → nothing. */
export function toScopeParams(scope) {
  if (scope?.mode === MODES.USER && isLocation(scope.location)) {
    return { lat: scope.location.lat, lng: scope.location.lng };
  }
  if (scope?.mode === MODES.AREA && scope.areaKey) {
    return { area: scope.areaKey };
  }
  return {};
}

/**
 * Query string carrying the scope into a page URL (/mon/{key}): the area
 * key, or near=1 for "near me" — never coordinates.
 */
export function scopeSearch(scope) {
  if (scope?.mode === MODES.AREA && scope.areaKey) return `?area=${encodeURIComponent(scope.areaKey)}`;
  if (scope?.mode === MODES.USER) return '?near=1';
  return '';
}

/**
 * The same, from a server response's scope (ScopeDTO: locationSource and
 * area key) — so a link carries the scope an answer was actually made in.
 */
export function scopeSearchOf(scopeDto) {
  if (scopeDto?.locationSource === MODES.AREA && scopeDto.area?.key) return `?area=${encodeURIComponent(scopeDto.area.key)}`;
  if (scopeDto?.locationSource === MODES.USER) return '?near=1';
  return '';
}

/**
 * In-memory cache key. Coordinates are rounded to 3 decimals (≈100 m) and the
 * key never leaves the tab.
 */
export function scopeKey(scope) {
  if (scope?.mode === MODES.USER && isLocation(scope.location)) {
    return `USER:${scope.location.lat.toFixed(3)},${scope.location.lng.toFixed(3)}`;
  }
  if (scope?.mode === MODES.AREA && scope.areaKey) return `AREA:${scope.areaKey}`;
  return MODES.CITY_CENTER;
}

/** The phrase for a scope before the server has answered ("Quanh Quận 1"). */
export function scopeLabel(scope) {
  if (scope?.mode === MODES.USER) return 'Gần bạn';
  if (scope?.mode === MODES.AREA) return scope.areaLabel ? `Quanh ${scope.areaLabel}` : 'Quanh khu vực đã chọn';
  return 'Quanh trung tâm TP.HCM';
}

/** "Quanh Quận 1" → "quanh Quận 1", for use inside a sentence. */
export function inSentence(label) {
  return label ? label.charAt(0).toLocaleLowerCase('vi') + label.slice(1) : '';
}

export function isLocation(location) {
  return Boolean(location)
    && Number.isFinite(location.lat) && Number.isFinite(location.lng)
    && Math.abs(location.lat) <= 90 && Math.abs(location.lng) <= 180;
}
