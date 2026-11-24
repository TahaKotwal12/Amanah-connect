import { expect, test } from '@playwright/test'
import { fakeApi } from './support/fakeApi.ts'

/**
 * The installable-app behaviour, against the production build (service workers only register
 * there). The rule that matters most: the worker caches static files and never an API response.
 */
test.use({ serviceWorkers: 'allow' })

test('the manifest and icons make the app installable', async ({ page, request }) => {
  await fakeApi(page, 'GUEST')
  await page.goto('/')
  const href = await page.locator('link[rel="manifest"]').getAttribute('href')
  const manifest = (await (await request.get(href ?? '/manifest.webmanifest')).json()) as {
    display: string
    start_url: string
    theme_color: string
    icons: Array<{ src: string; sizes: string; purpose?: string }>
  }
  expect(manifest.display).toBe('standalone')
  expect(manifest.theme_color).toBe('#07363E')
  const sizes = manifest.icons.map((icon) => `${icon.sizes}:${icon.purpose ?? 'any'}`)
  expect(sizes).toEqual(expect.arrayContaining(['192x192:any', '512x512:any', '512x512:maskable']))
  for (const icon of manifest.icons) {
    expect((await request.get(icon.src)).ok(), icon.src).toBe(true)
  }
})

test('the service worker caches static files only, never the API, and the shell opens offline', async ({
  page,
  context,
}) => {
  await fakeApi(page, 'COMMUNITY_ADMIN')
  await page.goto('/admin/dashboard')
  await expect(page.getByRole('heading', { level: 1 })).toBeVisible()

  // Wait for the worker to take control, then load again so this page's own files go through it.
  await page.evaluate(async () => {
    await navigator.serviceWorker.ready
  })
  await page.reload()
  await expect(page.getByRole('heading', { level: 1 })).toBeVisible()
  await page.waitForFunction(() => navigator.serviceWorker.controller !== null)
  await page.reload() // the API calls above (members, dashboard) have now run under the worker
  await expect(page.getByRole('heading', { level: 1 })).toBeVisible()

  const cached = await page.evaluate(async () => {
    const urls: string[] = []
    for (const name of await caches.keys()) {
      for (const request of await (await caches.open(name)).keys()) urls.push(request.url)
    }
    return urls
  })
  expect(cached.some((url) => url.includes('/assets/'))).toBe(true)
  // Not one API response, and nothing from another site, is in any cache.
  expect(cached.filter((url) => url.includes('/api/'))).toEqual([])
  expect(cached.filter((url) => new URL(url).origin !== new URL(page.url()).origin)).toEqual([])

  // With no connection the app shell still opens, and says it is offline.
  await context.setOffline(true)
  await page.reload()
  await expect(page.getByTestId('connection-status')).toContainText('You are offline')
  await context.setOffline(false)
})

test('no response from the API is ever stored by the browser cache layer we control', async ({
  page,
}) => {
  await fakeApi(page, 'COMMUNITY_ADMIN')
  await page.goto('/admin/members')
  await expect(page.getByRole('cell', { name: 'Asha Rao', exact: true })).toBeVisible()
  const hasApi = await page.evaluate(async () => {
    for (const name of await caches.keys()) {
      for (const request of await (await caches.open(name)).keys()) {
        if (new URL(request.url).pathname.startsWith('/api/')) return true
      }
    }
    return false
  })
  expect(hasApi).toBe(false)
})
