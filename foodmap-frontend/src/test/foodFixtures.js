// Response bodies (already unwrapped from the ApiResponse envelope, as
// apiClient returns them) for "Thèm gì?" tests. Counts and order mirror the
// real dev data (2026-10-03); a few entries are deliberately unusual.

const food = (key, label, kind, parentCategory, tone, count, radiusMeters, radiusWidened = false) =>
  ({ key, label, kind, parentCategory, tone, count, countCapped: false, radiusMeters, radiusWidened });

export const CITY_SCOPE = {
  locationSource: 'CITY_CENTER', area: null, radiusMeters: 3000, radiusWidened: false, label: 'Quanh trung tâm TP.HCM',
};

/** Catalog order — NOT count order (cà phê 71 first, then trà sữa 2, bún 3 …). */
export const cityFoods = () => ({
  scope: CITY_SCOPE,
  foods: [
    food('ca-phe', 'Cà phê', 'DRINK', 'coffee', 'taro', 71, 3000),
    food('tra-sua', 'Trà sữa', 'DRINK', 'coffee', 'taro', 2, 5000, true),
    food('bun', 'Bún', 'MEAL', 'noodle', 'chili', 3, 3000),
    food('pho', 'Phở', 'MEAL', 'noodle', 'chili', 3, 3000),
    food('pizza', 'Pizza', 'MEAL', 'pizza', 'crust', 1, 5000, true),
    food('sushi', 'Sushi', 'MEAL', 'japanese', 'dragonfruit', 4, 3000),
  ],
  unavailableCount: 4,
});

export const userFoods = () => ({
  scope: { locationSource: 'USER', area: null, radiusMeters: 1000, radiusWidened: false, label: 'Gần bạn' },
  foods: [
    food('ca-phe', 'Cà phê', 'DRINK', 'coffee', 'taro', 12, 1000),
    food('pho', 'Phở', 'MEAL', 'noodle', 'chili', 1, 1000),
    food('sushi', 'Sushi', 'MEAL', 'japanese', 'dragonfruit', 4, 3000, true),
  ],
  unavailableCount: 17,
});

export const binhThanhFoods = () => ({
  scope: { locationSource: 'AREA', area: { key: 'binh-thanh', label: 'Bình Thạnh' }, radiusMeters: 2500, radiusWidened: false, label: 'Quanh Bình Thạnh' },
  foods: [
    food('ca-phe', 'Cà phê', 'DRINK', 'coffee', 'taro', 29, 2500),
    food('lau', 'Lẩu', 'MEAL', 'hotpot', 'chili', 2, 2500),
    food('che', 'Chè', 'DESSERT', 'dessert', 'dragonfruit', 1, 2500),
  ],
  unavailableCount: 17,
});

export const emptyFoods = (scope = CITY_SCOPE) => ({ scope, foods: [], unavailableCount: 20 });

export const catalogBody = () => ({
  version: '8be56c7b98fa238c',
  foods: [
    { key: 'ca-phe', label: 'Cà phê', kind: 'DRINK', parentCategory: 'coffee', tone: 'taro', discoverable: true, order: 10, artKey: 'ca-phe' },
    { key: 'pho', label: 'Phở', kind: 'MEAL', parentCategory: 'noodle', tone: 'chili', discoverable: true, order: 50, artKey: 'pho' },
    { key: 'tra', label: 'Trà', kind: 'DRINK', parentCategory: 'coffee', tone: 'taro', discoverable: false, order: null, artKey: 'tra' },
  ],
  areas: [
    { key: 'quan-1', label: 'Quận 1', order: 10 },
    { key: 'quan-3', label: 'Quận 3', order: 20 },
    { key: 'binh-thanh', label: 'Bình Thạnh', order: 60 },
    { key: 'thu-duc', label: 'Thủ Đức (An Phú – Thảo Điền)', order: 100 },
  ],
  categories: [],
});

/** An axios-shaped HTTP error. */
export function httpError(status, { headers = {}, message } = {}) {
  const error = new Error(`Request failed with status code ${status}`);
  error.response = { status, headers, data: message ? { message } : {} };
  return error;
}

export function networkError() {
  const error = new Error('Network Error');
  error.code = 'ERR_NETWORK';
  return error;
}
