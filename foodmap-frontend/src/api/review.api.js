import apiClient from './client';

export const reviewApi = {
  /** GET /restaurants/:restaurantId/reviews?page=&size= */
  getForRestaurant: (restaurantId, params) =>
    apiClient.get(`/restaurants/${restaurantId}/reviews`, { params }),

  /**
   * POST /restaurants/:restaurantId/reviews
   * @param {{ rating, comment, foodId? }} payload
   */
  create: (restaurantId, payload) =>
    apiClient.post(`/restaurants/${restaurantId}/reviews`, payload),

  /** DELETE /restaurants/:restaurantId/reviews/:reviewId */
  delete: (restaurantId, reviewId) =>
    apiClient.delete(`/restaurants/${restaurantId}/reviews/${reviewId}`),
};
