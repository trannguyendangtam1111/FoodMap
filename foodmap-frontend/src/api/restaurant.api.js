import apiClient from './client';

export const restaurantApi = {
  /**
   * GET /restaurants/nearby
   * @param {{ lat, lng, radius?: number }} params
   */
  getNearby: (params) => apiClient.get('/restaurants/nearby', { params }),

  /**
   * GET /restaurants/viewport
   * @param {{ swLat, swLng, neLat, neLng }} params
   * @param {import('axios').AxiosRequestConfig} [config]  e.g. { signal }
   */
  getViewport: (params, config = {}) =>
    apiClient.get('/restaurants/viewport', { params, ...config }),

  /** GET /restaurants/:id */
  getById: (id) => apiClient.get(`/restaurants/${id}`),

  /**
   * GET /restaurants/search
   * @param {{ query, page?, size? }} params
   * @param {import('axios').AxiosRequestConfig} [config]  e.g. { signal }
   */
  search: (params, config = {}) => apiClient.get('/restaurants/search', { params, ...config }),
};
