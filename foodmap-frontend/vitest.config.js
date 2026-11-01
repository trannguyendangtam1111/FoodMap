import { defineConfig } from 'vitest/config';
import react from '@vitejs/plugin-react';

// Unit/component tests (jsdom). Kept separate from vite.config.js so the
// dev server and build configuration stay untouched.
export default defineConfig({
  plugins: [react()],
  test: {
    environment: 'jsdom',
    setupFiles: ['./src/test/setup.js'],
    include: ['src/**/*.test.{js,jsx}'],
    css: false,
    restoreMocks: true,
  },
});
