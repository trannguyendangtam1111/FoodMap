/**
 * Test fixtures shaped like the food page's responses (already unwrapped from
 * the ApiResponse envelope): GET /decisions/foods/{key}/restaurants and
 * GET /decisions/today?food=. Restaurants are real FoodMap rows with the
 * server's classification (foodKeys, category). Tests only.
 */
import { AREA_SCOPE, CITY_SCOPE, USER_SCOPE } from './foodDecisionFixtures';

export { AREA_SCOPE, CITY_SCOPE, USER_SCOPE };

export const PHO_FOOD = { key: 'pho', label: 'Phở', kind: 'MEAL', parentCategory: 'noodle', tone: 'chili', artKey: 'pho' };

export const PHO_HOA = {
  id: 1, name: 'Phở Hòa Pasteur', address: '260C Pasteur, Phường 8, Quận 3, TP.HCM',
  latitude: 10.7762, longitude: 106.6892, avgRating: 4.5, reviewCount: 4, priceLevel: 1, cuisineType: 'Vietnamese',
  photos: [], foodKeys: ['pho'], category: 'noodle',
};
/** Menu evidence only: the name says nothing about phở, the server's menu match does. */
export const NHA_HANG_NGON = {
  id: 9, name: 'Nhà Hàng Ngon', address: '160 Pasteur, Bến Nghé, Quận 1, TP.HCM',
  latitude: 10.7779, longitude: 106.6952, avgRating: 4.33, reviewCount: 3, priceLevel: 2, cuisineType: 'Vietnamese',
  photos: [], foodKeys: ['banh-xeo', 'bun', 'pho'], category: 'vietnamese',
};
export const PHO_27 = {
  id: 27, name: 'Phở', address: 'Phở, 27 Đường số 7, Phường 3, Bình Thạnh, Hồ Chí Minh',
  latitude: 10.79526, longitude: 106.69105, avgRating: 0, reviewCount: 0, photos: [], foodKeys: ['pho'], category: 'noodle',
};

export const byName = (restaurant) => ({ restaurant, evidence: { source: 'NAME' } });
export const byMenu = (restaurant, dishName) => ({ restaurant, evidence: { source: 'MENU', dishName } });

/** The server's list body. Items are passed in the server's (ranking) order. */
export function foodRestaurants(items, { total = items.length, page = 0, size = 12, scope = CITY_SCOPE, food = PHO_FOOD } = {}) {
  return { scope, food: { key: food.key, label: food.label }, totalElements: total, page, size, items };
}

/** n distinct phở restaurants, for paging. */
export function manyPho(n, from = 100) {
  return Array.from({ length: n }, (_, i) => byName({
    id: from + i, name: `Phở số ${from + i}`, address: `${from + i} Nguyễn Trãi, Quận 1`,
    avgRating: 0, reviewCount: 0, photos: [], foodKeys: ['pho'], category: 'noodle',
  }));
}

let seq = 0;

/** /decisions/today?food= with a restaurant (city centre unless overridden). */
export function suggestion(restaurant, overrides = {}) {
  seq += 1;
  const area = overrides.locationSource === 'AREA' ? 'Quanh Bình Thạnh' : 'Quanh trung tâm TP.HCM';
  return {
    decisionId: `sugg-${seq}`,
    restaurant,
    distanceMeters: null,
    reasons: [
      { code: 'AREA', label: area },
      { code: 'FOOD_NAME', label: 'Tên quán cho thấy có phở' },
    ],
    candidateCount: 3,
    fallbackLevel: 3,
    locationSource: 'CITY_CENTER',
    radiusMeters: 3000,
    mood: null,
    moodMatched: null,
    food: { key: 'pho', label: 'Phở' },
    area: null,
    radiusWidened: false,
    emptyReason: null,
    ...overrides,
  };
}

/** /decisions/today?food= without a restaurant: NO_FOOD_IN_SCOPE or ALL_SHOWN. */
export function noSuggestion(emptyReason = 'NO_FOOD_IN_SCOPE', overrides = {}) {
  return {
    decisionId: null, restaurant: null, distanceMeters: null, reasons: [], candidateCount: 0, fallbackLevel: 3,
    locationSource: 'CITY_CENTER', radiusMeters: 5000, mood: null, moodMatched: null,
    food: { key: 'pho', label: 'Phở' }, area: null, radiusWidened: emptyReason !== 'ALL_SHOWN', emptyReason, ...overrides,
  };
}
