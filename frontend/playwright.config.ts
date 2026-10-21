import { defineConfig, devices } from '@playwright/test'

const baseURL = process.env.E2E_BASE_URL ?? 'http://localhost:4173'

export default defineConfig({
  testDir: './e2e',
  fullyParallel: true,
  // Each test drives a real browser; a handful at a time keeps timings honest on a busy laptop.
  workers: process.env.CI ? 2 : 3,
  forbidOnly: !!process.env.CI,
  retries: process.env.CI ? 2 : 0,
  reporter: process.env.CI ? [['list'], ['html', { open: 'never' }]] : 'list',
  use: { baseURL, trace: 'on-first-retry' },
  projects: [{ name: 'chromium', use: { ...devices['Desktop Chrome'] } }],
  // Serve the production build. Set E2E_BASE_URL to run against an already running stack instead.
  webServer: process.env.E2E_BASE_URL
    ? undefined
    : {
        command: 'npm run build && npm run preview -- --strictPort',
        url: baseURL,
        reuseExistingServer: !process.env.CI,
        timeout: 180_000,
      },
})
