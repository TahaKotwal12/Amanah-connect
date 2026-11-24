/*
 * Amanah Connect service worker.
 *
 * It caches STATIC ASSETS ONLY, so the app opens fast and the shell still loads with no connection.
 * It never caches, and never even looks at, anything under /api/: money, member data and tokens are
 * always fetched live. Rules:
 *   - /assets/*  (content-hashed, so a cached copy is never stale)    -> cache first
 *   - /icons/*, /brand/*, the manifest                                 -> stale while revalidate
 *   - page navigations                                                 -> network first, and the last
 *     good copy of the HTML shell is used only when the network fails
 *   - everything else (including all /api/ calls, POSTs, other origins) -> not handled here
 * Bump VERSION to drop every cache.
 */
const VERSION = 'v1'
const STATIC = `amanah-static-${VERSION}`
const SHELL = `amanah-shell-${VERSION}`

self.addEventListener('install', () => {
  // A new worker waits until the page asks it to take over (see lib/pwa.ts), so a reload prompt
  // never swaps files under a form someone is filling in.
})

self.addEventListener('message', (event) => {
  if (event.data === 'SKIP_WAITING') void self.skipWaiting()
})

self.addEventListener('activate', (event) => {
  event.waitUntil(
    (async () => {
      const keep = new Set([STATIC, SHELL])
      for (const name of await caches.keys()) {
        if (name.startsWith('amanah-') && !keep.has(name)) await caches.delete(name)
      }
      await self.clients.claim()
    })(),
  )
})

function isHashedAsset(url) {
  return url.pathname.startsWith('/assets/')
}
function isStaticFile(url) {
  return (
    url.pathname.startsWith('/icons/') ||
    url.pathname.startsWith('/brand/') ||
    url.pathname === '/manifest.webmanifest'
  )
}

self.addEventListener('fetch', (event) => {
  const request = event.request
  if (request.method !== 'GET') return
  const url = new URL(request.url)
  if (url.origin !== self.location.origin) return
  if (url.pathname.startsWith('/api/')) return // never: see the note at the top

  if (isHashedAsset(url)) {
    event.respondWith(
      (async () => {
        const cache = await caches.open(STATIC)
        const hit = await cache.match(request)
        if (hit) return hit
        const response = await fetch(request)
        if (response.ok) void cache.put(request, response.clone())
        return response
      })(),
    )
    return
  }

  if (isStaticFile(url)) {
    event.respondWith(
      (async () => {
        const cache = await caches.open(STATIC)
        const hit = await cache.match(request)
        const refresh = fetch(request)
          .then((response) => {
            if (response.ok) void cache.put(request, response.clone())
            return response
          })
          .catch(() => hit)
        return hit ?? (await refresh)
      })(),
    )
    return
  }

  if (request.mode === 'navigate') {
    event.respondWith(
      (async () => {
        const cache = await caches.open(SHELL)
        try {
          const response = await fetch(request)
          // Keep one copy of the shell. The HTML holds no personal data: it is the same file for
          // everyone, and the app loads its data from the API after it starts.
          if (response.ok && response.headers.get('content-type')?.includes('text/html')) {
            void cache.put('/', response.clone())
          }
          return response
        } catch {
          const shell = await cache.match('/')
          if (shell) return shell
          throw new Error('offline and no cached shell')
        }
      })(),
    )
  }
})
