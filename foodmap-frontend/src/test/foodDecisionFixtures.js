/**
 * Test fixtures shaped like GET /api/v1/decisions/food responses (already
 * unwrapped from the ApiResponse envelope). Foods are real catalog entries
 * with real dev-data counts. Tests only — the app never picks these.
 */
export const PHO = { key: 'pho', label: 'Phở', kind: 'MEAL', parentCategory: 'noodle', tone: 'chili', artKey: 'pho' };
export const BUN_BO = { key: 'bun-bo', label: 'Bún bò', kind: 'MEAL', parentCategory: 'noodle', tone: 'chili', artKey: 'bun-bo' };
export const SUSHI = { key: 'sushi', label: 'Sushi', kind: 'MEAL', parentCategory: 'japanese', tone: 'dragonfruit', artKey: 'sushi' };
export const LAU = { key: 'lau', label: 'Lẩu', kind: 'MEAL', parentCategory: 'hotpot', tone: 'chili', artKey: 'lau' };

export const CITY_SCOPE = {
  locationSource: 'CITY_CENTER', area: null, radiusMeters: 3000, radiusWidened: false, label: 'Quanh trung tâm TP.HCM',
};
export const USER_SCOPE = { locationSource: 'USER', area: null, radiusMeters: 1000, radiusWidened: false, label: 'Gần bạn' };
export const AREA_SCOPE = {
  locationSource: 'AREA', area: { key: 'binh-thanh', label: 'Bình Thạnh' }, radiusMeters: 2500, radiusWidened: false, label: 'Quanh Bình Thạnh',
};

let seq = 0;

/** A food the server chose (city centre unless overridden). */
export function foodDecision(food, overrides = {}) {
  seq += 1;
  return {
    decisionId: `food-${seq}`,
    food,
    restaurantCount: 3,
    radiusMeters: 3000,
    radiusWidened: false,
    scope: CITY_SCOPE,
    candidateFoodCount: 14,
    emptyReason: null,
    ...overrides,
  };
}

/** 200 with no food: NO_FOOD_IN_SCOPE or ALL_SHOWN. */
export function noFood(emptyReason = 'NO_FOOD_IN_SCOPE', scope = CITY_SCOPE) {
  return {
    decisionId: null, food: null, restaurantCount: 0, radiusMeters: 5000, radiusWidened: emptyReason !== 'ALL_SHOWN',
    scope, candidateFoodCount: 0, emptyReason,
  };
}
