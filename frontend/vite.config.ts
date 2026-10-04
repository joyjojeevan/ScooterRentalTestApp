import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173,
    proxy: {
      // Keep the browser's Host header so the backend sees proxied calls as
      // same-origin. The string shorthand sets changeOrigin: true, which makes
      // Spring's CORS filter reject any dev port not in CORS_ORIGINS with 403.
      '/api': { target: 'http://localhost:8080', changeOrigin: false },
      // Local stand-in for the payment provider's public API (mock mode only).
      '/mock-stripe': { target: 'http://localhost:8080', changeOrigin: false },
    },
  },
});
