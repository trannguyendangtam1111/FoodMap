/**
 * Restaurant categories — presentation metadata only.
 *
 * The server classifies every restaurant (name + FoodMap menu, against its
 * food catalog) and sends the result as `category` on restaurant DTOs. The
 * frontend never infers it: this file only maps that key to an illustration
 * tone and a label. A missing or unknown key is `other`, which the UI never
 * labels.
 */

import { getLang } from '../i18n';

// `label` follows the UI language (vi / en); both live here, next to the tone.
const category = (key, vi, en, tone) => Object.freeze({
  key, tone,
  get label() { return getLang() === 'vi' ? vi : en; },
});

// tone = Tailwind accent key from the design system (see tailwind.config.js)
export const CATEGORIES = {
  noodle:     category('noodle',     'Bún, phở, mì',      'Noodles & phở',        'chili'),
  hotpot:     category('hotpot',     'Lẩu & nướng',       'Hotpot & grill',       'chili'),
  rice:       category('rice',       'Cơm & cháo',        'Rice & congee',        'turmeric'),
  vietnamese: category('vietnamese', 'Món Việt',          'Vietnamese',           'turmeric'),
  bakery:     category('bakery',     'Bánh mì & bánh',    'Bánh mì & bakery',     'crust'),
  pizza:      category('pizza',      'Pizza & mì Ý',      'Pizza & pasta',        'crust'),
  coffee:     category('coffee',     'Cà phê & trà sữa',  'Coffee & milk tea',    'taro'),
  vegetarian: category('vegetarian', 'Món chay',          'Vegetarian',           'pandan'),
  dessert:    category('dessert',    'Chè & tráng miệng', 'Desserts',             'dragonfruit'),
  japanese:   category('japanese',   'Món Nhật',          'Japanese',             'dragonfruit'),
  seafood:    category('seafood',    'Hải sản',           'Seafood',              'brand'),
  other:      category('other',      'Quán ăn',           'Eatery',               'brand'),
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
