import react from '@vitejs/plugin-react'
import { defineConfig } from 'vite'

// Each backend service is reached through the dev server, so the browser only talks to one
// origin. Override targets with ETHERM_*_URL when the services run elsewhere (e.g. compose).
const service = (envName: string, fallback: string, prefix: string) => ({
  target: process.env[envName] ?? fallback,
  changeOrigin: true,
  rewrite: (path: string) => path.replace(new RegExp(`^${prefix}`), ''),
})

// https://vite.dev/config/
export default defineConfig({
  plugins: [react()],
  server: {
    proxy: {
      '/svc/ingestion': service('ETHERM_INGESTION_URL', 'http://localhost:8081', '/svc/ingestion'),
      '/svc/normalization': service('ETHERM_NORMALIZATION_URL', 'http://localhost:8082', '/svc/normalization'),
      '/svc/rag': service('ETHERM_RAG_URL', 'http://localhost:8084', '/svc/rag'),
    },
  },
})
