import { fileURLToPath, URL } from 'node:url'
import react from '@vitejs/plugin-react'
import { visualizer } from 'rollup-plugin-visualizer'
import { loadEnv } from 'vite'
import { defineConfig } from 'vitest/config'
import { poster } from './vite-plugin-poster.ts'
import { siteMeta } from './vite-plugin-site-meta.ts'

// https://vite.dev/config/
export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, process.cwd(), 'VITE_')
  // Dev and preview forward /api to the Spring Boot API so the browser stays same-origin
  // (the refresh cookie is SameSite=Strict and scoped to /api/v1/auth).
  const proxy = {
    '/api': { target: env.VITE_API_URL || 'http://localhost:8080', changeOrigin: false },
  }

  return {
    plugins: [
      react(),
      siteMeta(env.VITE_SITE_URL ?? ''),
      poster(),
      // `npm run analyze` writes reports/bundle.html: a treemap of what is in each chunk (gzip sizes).
      ...(process.env.ANALYZE
        ? [
            visualizer({
              filename: 'reports/bundle.html',
              gzipSize: true,
              template: 'treemap',
              emitFile: false,
            }),
          ]
        : []),
    ],
    resolve: {
      alias: { '@': fileURLToPath(new URL('./src', import.meta.url)) },
    },
    server: {
      port: 5173,
      proxy,
      // Tests read ../backend/.../ErrorCode.java to check every error code has friendly copy.
      // Only opened up under vitest, never for `npm run dev`.
      ...(mode === 'test' ? { fs: { allow: ['..'] } } : {}),
    },
    preview: { port: 4173, proxy },
    build: {
      // Content-hashed file names are the default (assets/[name]-[hash].js), so Nginx can serve
      // /assets/* as immutable. 'hidden' emits .map files without a sourceMappingURL comment:
      // upload them to the error tracker and do not copy them into the Nginx image.
      sourcemap: 'hidden',
      // dist/.vite/manifest.json lists each chunk and what it imports: scripts/check-bundle.mjs reads it.
      manifest: true,
      target: 'es2023',
    },
    test: {
      environment: 'jsdom',
      globals: true,
      setupFiles: ['./src/test/setup.ts'],
      include: ['src/**/*.{test,spec}.{ts,tsx}'],
      css: false,
      // The first test to open a page also loads its lazy chunks, which is slow when many test files
      // start at once.
      testTimeout: 20_000,
    },
  }
})
