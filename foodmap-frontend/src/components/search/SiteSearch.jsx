import { useNavigate } from 'react-router-dom';
import SearchBar from './SearchBar';
import { matchFoods } from '../../api/catalog.api';
import useLocationScope from '../../hooks/useLocationScope';
import { scopeSearch } from '../../utils/scope';

export const SEARCH_INPUT_ID = 'site-search';
export const SEARCH_PLACEHOLDER = 'Tìm món, tìm quán…';

/**
 * SiteSearch — SearchBar outside the map page, food-first (Master
 * Specification §6.8): its "Món ăn" section lists the server's catalog foods
 * (GET /catalog/foods/match) and opens the food page.
 *
 *   a food suggestion        → /mon/{key}
 *   Enter on a food's name   → /mon/{key}, when the server says the WHOLE query
 *                              names that food ("phở", "pho", "bún bò")
 *   Enter on anything else   → the map's text search, /map?q= (a restaurant
 *                              name such as "Phở Hòa Pasteur", or no food)
 *   a restaurant suggestion  → /restaurants/{id} (SearchBar's default)
 *
 * The food page opens in the scope already chosen in this tab (?area= or
 * ?near=1 — never coordinates), like "Thèm gì?". Nothing is matched here:
 * the food key is always the server's.
 */
export default function SiteSearch({ size = 'md', className = '' }) {
  const navigate = useNavigate();
  const { scope } = useLocationScope();
  const openFood = (food) => navigate(`/mon/${encodeURIComponent(food.key)}${scopeSearch(scope)}`);
  const textSearch = (term) => navigate(`/map?q=${encodeURIComponent(term)}`);

  const onSearch = async (term) => {
    let intent = null;
    try {
      ({ intent } = await matchFoods(term));
    } catch {
      // the food lookup failed: keep the text search, never guess a food
    }
    if (intent) openFood(intent);
    else textSearch(term);
  };

  return (
    <SearchBar
      foodSource="catalog"
      inputId={SEARCH_INPUT_ID}
      placeholder={SEARCH_PLACEHOLDER}
      size={size}
      className={className}
      onFoodSelect={openFood}
      onSearch={onSearch}
    />
  );
}
