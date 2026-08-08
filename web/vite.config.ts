import { fileURLToPath, URL } from 'node:url'
import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

const configuredHosts = process.env.VITE_ALLOWED_HOSTS
  ?.split(',')
  .map((host) => host.trim())
  .filter(Boolean)

export default defineConfig({
  plugins: [vue()],
  resolve: {
    alias: {
      '@': fileURLToPath(new URL('./src', import.meta.url)),
    },
  },
  server: {
    host: '0.0.0.0',
    port: 5173,
    allowedHosts: configuredHosts?.length ? configuredHosts : ['localhost'],
    watch: {
      ignored: [
        '**/test-results/**',
        '**/playwright-report/**',
        '**/tests/**/*-snapshots/**',
      ],
    },
    proxy: {
      '/api': {
        target: process.env.VITE_DEV_API_TARGET ?? 'http://localhost:8080',
        changeOrigin: true,
      },
      // Upload URLs are presigned against the web origin. Keep the incoming Host
      // header so MinIO verifies the same SigV4 host the API signed.
      '/rag-assets': {
        target: process.env.VITE_DEV_STORAGE_TARGET ?? 'http://localhost:9000',
        changeOrigin: false,
      },
    },
  },
})
