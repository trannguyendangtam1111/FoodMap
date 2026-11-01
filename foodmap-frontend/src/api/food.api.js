import apiClient from './client';

export const foodApi = {
  /** GET /foods/search?query=&page=&size= */
  search: (params, config = {}) => apiClient.get('/foods/search', { params, ...config }),

  /** GET /foods/:id */
  getById: (id) => apiClient.get(`/foods/${id}`),

  /** GET /foods/slug/:slug */
  getBySlug: (slug) => apiClient.get(`/foods/slug/${slug}`),

  /** GET /foods/trending */
  getTrending: () => apiClient.get('/foods/trending'),

  /**
   * GET /foods/:id/restaurants?page=&size=
   * Dish-filter mode — returns all restaurants serving this food.
   * @param {number} foodId
   * @param {{ page?: number, size?: number }} params
   * @param {import('axios').AxiosRequestConfig} [config]  e.g. { signal }
   */
  getRestaurants: (foodId, params = {}, config = {}) =>
    apiClient.get(`/foods/${foodId}/restaurants`, { params, ...config }),
};
