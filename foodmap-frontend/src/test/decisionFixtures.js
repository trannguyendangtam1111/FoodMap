/**
 * Test fixtures shaped like GET /api/v1/decisions/today responses.
 * Restaurants are real FoodMap rows (RestaurantSummaryDTO fields only,
 * nulls omitted as the API does). Tests only — the app never picks these.
 */
export const BANH_MI = {
  id: 4, name: 'Bánh Mì Huỳnh Hoa', address: '26 Lê Thị Riêng, Phường Bến Thành, Quận 1, TP.HCM',
  latitude: 10.7764, longitude: 106.7008, avgRating: 4.67, reviewCount: 3, priceLevel: 1, cuisineType: 'Vietnamese', photos: [],
};
export const BUN_DAU = {
  id: 82, name: 'Bún Đậu Ngõ Nhỏ Phố Nhỏ', address: 'Bún Đậu Ngõ Nhỏ Phố Nhỏ, 156C Pasteur, Bến Nghé, Quận 1, Hồ Chí Minh',
  latitude: 10.777030818000071, longitude: 106.69978307400004, avgRating: 0, reviewCount: 0, photos: [],
};
export const NYONYA = {
  id: 13, name: "Restaurant D'Nyonya", address: "Restaurant D'Nyonya, 56 Đông Du, Bến Nghé, Quận 1, Hồ Chí Minh",
  latitude: 10.776267026000028, longitude: 106.70471410400006, avgRating: 0, reviewCount: 0, photos: [],
};

let seq = 0;

/** A USER-location answer (fallbackLevel 0 unless overridden). */
export function userDecision(restaurant, { distanceMeters = 630, ...overrides } = {}) {
  seq += 1;
  return {
    decisionId: `test-${seq}`,
    restaurant,
    distanceMeters,
    reasons: [{ code: 'DISTANCE', label: `Cách bạn ${distanceMeters} m` }],
    candidateCount: 12,
    fallbackLevel: 0,
    locationSource: 'USER',
    radiusMeters: 1000,
    mood: null,
    moodMatched: null,
    ...overrides,
  };
}

/** A no-location answer from the city-centre pool (fallbackLevel 3). */
export function cityCentreDecision(restaurant, overrides = {}) {
  seq += 1;
  return {
    decisionId: `test-${seq}`,
    restaurant,
    distanceMeters: null,
    reasons: [{ code: 'AREA', label: 'Quanh trung tâm TP.HCM' }],
    candidateCount: 40,
    fallbackLevel: 3,
    locationSource: 'CITY_CENTER',
    radiusMeters: 3000,
    mood: null,
    moodMatched: null,
    ...overrides,
  };
}

/** 200 with no candidate. */
export const NO_RESULT = {
  decisionId: null, restaurant: null, distanceMeters: null, reasons: [],
  candidateCount: 0, fallbackLevel: 3, locationSource: 'CITY_CENTER', radiusMeters: 3000, mood: null, moodMatched: null,
};
