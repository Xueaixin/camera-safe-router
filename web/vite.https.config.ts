import { readFileSync } from 'node:fs';
import { fileURLToPath, URL } from 'node:url';

import vue from '@vitejs/plugin-vue';
import { defineConfig } from 'vite';

const certificate = fileURLToPath(new URL('./certs/dev-cert.pem', import.meta.url));
const privateKey = fileURLToPath(new URL('./certs/dev-key.pem', import.meta.url));

export default defineConfig({
  plugins: [vue()],
  resolve: {
    alias: {
      '@': fileURLToPath(new URL('./src', import.meta.url)),
    },
  },
  server: {
    https: {
      cert: readFileSync(certificate),
      key: readFileSync(privateKey),
    },
    proxy: {
      '/api': {
        target: 'http://127.0.0.1:8080',
        changeOrigin: true,
      },
    },
  },
});
