import { getLang } from './index';

/**
 * English names for the catalog foods that are generic food types, keyed by
 * the server's stable catalog key. Named dishes (phở, bún bò, bánh mì, …) and
 * names that are already English keep the catalog's own label in every
 * language, and so does any key not listed here.
 */
const EN_FOOD_NAMES = Object.freeze({
  'ca-phe': 'Coffee',
  'tra-sua': 'Milk tea',
  com: 'Rice',
  chao: 'Congee',
  lau: 'Hotpot',
  nuong: 'Grill',
  'hai-san': 'Seafood',
  chay: 'Veggie',
  che: 'Sweets',
  kem: 'Ice cream',
});

/** A catalog food's display name in the current language: the server's label unless an English name exists. */
export function foodName(food) {
  if (!food) return '';
  return (getLang() === 'en' && EN_FOOD_NAMES[food.key]) || food.label;
}
