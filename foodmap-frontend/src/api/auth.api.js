import apiClient from './client';

export const authApi = {
  /** POST /auth/register → AuthResponse */
  register: (payload) => apiClient.post('/auth/register', payload),

  /** POST /auth/login → AuthResponse */
  login: (payload) => apiClient.post('/auth/login', payload),

  /** POST /auth/refresh → AuthResponse */
  refresh: (refreshToken) => apiClient.post('/auth/refresh', { refreshToken }),

  /** POST /auth/logout — revokes the refresh token server-side */
  logout: (refreshToken) => apiClient.post('/auth/logout', { refreshToken }),
};
