import CoffeeArt from './foods/CoffeeArt';
import MilkTeaArt from './foods/MilkTeaArt';
import PhoArt from './foods/PhoArt';
import BunArt from './foods/BunArt';
import BunBoArt from './foods/BunBoArt';
import HuTieuArt from './foods/HuTieuArt';
import MiArt from './foods/MiArt';
import ComArt from './foods/ComArt';
import ChaoArt from './foods/ChaoArt';
import BanhMiArt from './foods/BanhMiArt';
import BanhXeoArt from './foods/BanhXeoArt';
import LauArt from './foods/LauArt';
import NuongArt from './foods/NuongArt';
import SeafoodArt from './foods/SeafoodArt';
import PizzaArt from './foods/PizzaArt';
import SushiArt from './foods/SushiArt';
import DimSumArt from './foods/DimSumArt';
import ChayArt from './foods/ChayArt';
import CheArt from './foods/CheArt';
import IceCreamArt from './foods/IceCreamArt';

/**
 * The one mapping from a catalog `artKey` (the food key unless the catalog
 * says otherwise) to its drawing. Nothing else in the UI branches on food
 * keys; a key missing here falls back (see FoodArt / FoodGlyph).
 *
 * A Map, so lookups never hit Object prototype names ("constructor", …).
 */
const REGISTRY = new Map([
  ['ca-phe', CoffeeArt],
  ['tra-sua', MilkTeaArt],
  ['pho', PhoArt],
  ['bun', BunArt],
  ['bun-bo', BunBoArt],
  ['hu-tieu', HuTieuArt],
  ['mi', MiArt],
  ['com', ComArt],
  ['chao', ChaoArt],
  ['banh-mi', BanhMiArt],
  ['banh-xeo', BanhXeoArt],
  ['lau', LauArt],
  ['nuong', NuongArt],
  ['hai-san', SeafoodArt],
  ['pizza', PizzaArt],
  ['sushi', SushiArt],
  ['dimsum', DimSumArt],
  ['chay', ChayArt],
  ['che', CheArt],
  ['kem', IceCreamArt],
]);

/** The drawing for an art key, or null when there is none. */
export function getFoodArt(artKey) {
  return typeof artKey === 'string' ? REGISTRY.get(artKey) ?? null : null;
}

/** Every art key with a drawing, in registry order. */
export const FOOD_ART_KEYS = Object.freeze([...REGISTRY.keys()]);
