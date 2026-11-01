import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

export default defineConfig({
  plugins: [react()],

  // Vite needs to pre-bundle the CommonJS Goong SDK so it works in ESM context
  optimizeDeps: {
    include: ['@goongmaps/goong-js'],
  },

  // Proxy /api calls to the Spring Boot backend during development.
  // This avoids CORS preflight for every request in dev mode.
  server: {
    port: 5173,
    // Fail instead of drifting to 5174, which the backend CORS config does not allow.
    strictPort: true,
    proxy: {
      '/api': {
        target: 'http://localhost:8082',
        changeOrigin: true,
      },
    },
  },
});
