import axios from 'axios';

// In dev the Vite proxy forwards /api → localhost:8082, so we don't need an
// absolute origin. In production VITE_API_BASE_URL must point to the backend.
const BASE_URL = import.meta.env.VITE_API_BASE_URL ?? '';

const apiClient = axios.create({
  baseURL: `${BASE_URL}/api/v1`,
  headers: { 'Content-Type': 'application/json' },
  timeout: 15_000,
});

// ── Request: attach JWT ───────────────────────────────────────────────────────
apiClient.interceptors.request.use((config) => {
  const token = localStorage.getItem('access_token');
  if (token) config.headers.Authorization = `Bearer ${token}`;
  return config;
});

// ── Response: normalise envelope + handle 401 ────────────────────────────────
// Success responses from FoodMap are wrapped: { success, data, timestamp }
// We unwrap them here so every caller just receives response.data directly.
apiClient.interceptors.response.use(
  (response) => {
    // Only unwrap FoodMap's own ApiResponse envelope
    if (response.data?.success === true && 'data' in response.data) {
      response.data = response.data.data;
    }
    return response;
  },
  (error) => {
    if (error.response?.status === 401) {
      localStorage.removeItem('access_token');
      localStorage.removeItem('refresh_token');
      // Avoid hard redirect inside interceptor — let ProtectedRoute handle it
      window.dispatchEvent(new CustomEvent('auth:expired'));
    }
    return Promise.reject(error);
  },
);

export default apiClient;
