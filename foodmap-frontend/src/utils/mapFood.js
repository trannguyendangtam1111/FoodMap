/**
 * Food on the /map page — pure helpers over data the page already has.
 *
 * Every food fact here comes from the server: a restaurant's `foodKeys`
 * (RestaurantSummaryDTO, primary first) and the catalog's foods (labels,
 * art, `discoverable`). Nothing is matched by name, nothing is inferred, and
 * every count is over the restaurants currently loaded for the map frame —
 * never a city-wide total.
 *
 * Plain ESM, no React, so it is unit-testable.
 */

const keysOf = (restaurant) => (Array.isArray(restaurant?.foodKeys) ? restaurant.foodKeys : []);

/** Whether the server classified this restaurant with `foodKey` (any position). */
export function matchesFood(restaurant, foodKey) {
  return Boolean(foodKey) && keysOf(restaurant).includes(foodKey);
}

/** foodKey → number of loaded restaurants carrying it. One pass. */
export function countFoods(restaurants) {
  const counts = new Map();
  for (const restaurant of Array.isArray(restaurants) ? restaurants : []) {
    for (const key of new Set(keysOf(restaurant))) counts.set(key, (counts.get(key) ?? 0) + 1);
  }
  return counts;
}

/**
 * The food pills of a restaurant: discoverable catalog foods only, in the
 * server's order, the selected food first when the restaurant has it, at most
 * `max` shown and the rest counted. `foods` is the catalog as a Map by key.
 */
export function pillFoods(foodKeys, foods, selectedKey = null, max = 3) {
  const seen = new Set();
  const list = [];
  for (const key of Array.isArray(foodKeys) ? foodKeys : []) {
    const food = foods?.get(key);
    if (!food?.discoverable || seen.has(key)) continue;
    seen.add(key);
    list.push(food);
  }
  const selected = list.findIndex((f) => f.key === selectedKey);
  if (selected > 0) list.unshift(...list.splice(selected, 1));
  return { shown: list.slice(0, max), more: Math.max(0, list.length - max) };
}

/**
 * How a map pin looks:
 *   'plain'  no server food — the original utensils pin
 *   'food'   the glyph of its primary food (foodKeys[0])
 *   'match'  a food is selected and this restaurant has it — that food's glyph
 *   'dim'    a food is selected and this restaurant doesn't have it
 * The selected restaurant is never dimmed (it keeps its neutral look).
 * Returns { state, glyphKey } — glyphKey is a food key or null.
 */
export function pinAppearance(restaurant, { foodKey = null, selected = false } = {}) {
  const primary = keysOf(restaurant)[0] ?? null;
  if (foodKey) {
    if (matchesFood(restaurant, foodKey)) return { state: 'match', glyphKey: foodKey };
    if (!selected) return { state: 'dim', glyphKey: null };
  }
  return primary ? { state: 'food', glyphKey: primary } : { state: 'plain', glyphKey: null };
}

/** "4 quán có Phở trong khung bản đồ" — the count always names its scope. */
export function foodCountText(count, label) {
  return `${count} quán có ${label} trong khung bản đồ`;
}
