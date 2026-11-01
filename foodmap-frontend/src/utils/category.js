/**
 * Restaurant categories — presentation metadata only.
 *
 * The server classifies every restaurant (name + FoodMap menu, against its
 * food catalog) and sends the result as `category` on restaurant DTOs. The
 * frontend never infers it: this file only maps that key to an illustration
 * tone and a label. A missing or unknown key is `other`, which the UI never
 * labels.
 */

// tone = Tailwind accent key from the design system (see tailwind.config.js)
export const CATEGORIES = {
  noodle:     { key: 'noodle',     label: 'Bún, phở, mì',      tone: 'chili' },
  hotpot:     { key: 'hotpot',     label: 'Lẩu & nướng',       tone: 'chili' },
  rice:       { key: 'rice',       label: 'Cơm & cháo',        tone: 'turmeric' },
  vietnamese: { key: 'vietnamese', label: 'Món Việt',          tone: 'turmeric' },
  bakery:     { key: 'bakery',     label: 'Bánh mì & bánh',    tone: 'crust' },
  pizza:      { key: 'pizza',      label: 'Pizza & mì Ý',      tone: 'crust' },
  coffee:     { key: 'coffee',     label: 'Cà phê & trà sữa',  tone: 'taro' },
  vegetarian: { key: 'vegetarian', label: 'Món chay',          tone: 'pandan' },
  dessert:    { key: 'dessert',    label: 'Chè & tráng miệng', tone: 'dragonfruit' },
  japanese:   { key: 'japanese',   label: 'Món Nhật',          tone: 'dragonfruit' },
  seafood:    { key: 'seafood',    label: 'Hải sản',           tone: 'brand' },
  other:      { key: 'other',      label: 'Quán ăn',           tone: 'brand' },
};

/**
 * The server's category of a restaurant (summary or detail DTO), as
 * presentation metadata. No category from the server → other.
 */
export function categoryOf(restaurant) {
  const key = restaurant?.category;
  return typeof key === 'string' && Object.hasOwn(CATEGORIES, key) ? CATEGORIES[key] : CATEGORIES.other;
}

/** True when the category carries real information worth labelling. */
export const isKnownCategory = (category) => !!category && category.key !== 'other';
