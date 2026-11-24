import { scrubEvent, scrubText, scrubUrl } from './scrub'

/**
 * Error reporting (Sentry), off unless VITE_SENTRY_DSN is set at build time. The SDK is loaded with
 * a dynamic import, so builds and visits without a DSN download none of it.
 *
 * Privacy settings, all deliberate:
 *   - default PII is not collected, and the user, cookies, headers and request bodies are dropped
 *   - no session replay and no performance tracing (replays would record member data on screen)
 *   - breadcrumbs keep navigation and clicks only: console, fetch and XHR entries are dropped
 *   - every message, URL and stack value is passed through scrub.ts before it is sent
 *   - the person's id is never set on the scope
 */
let reporter: { captureException: (error: unknown) => void } | undefined

export async function initMonitoring(): Promise<void> {
  const dsn = import.meta.env.VITE_SENTRY_DSN
  if (!dsn || reporter) return
  const Sentry = await import('@sentry/react')
  Sentry.init({
    dsn,
    environment: import.meta.env.VITE_SENTRY_ENV ?? import.meta.env.MODE,
    release: import.meta.env.VITE_RELEASE,
    tracesSampleRate: 0,
    replaysSessionSampleRate: 0,
    replaysOnErrorSampleRate: 0,
    maxBreadcrumbs: 30,
    beforeSend: (event) =>
      scrubEvent(event as unknown as Record<string, unknown>) as unknown as typeof event,
    beforeBreadcrumb: (breadcrumb) => {
      // Console output and network calls can carry personal data: keep clicks and navigation only.
      if (['console', 'fetch', 'xhr'].includes(breadcrumb.category ?? '')) return null
      if (breadcrumb.message) breadcrumb.message = scrubText(breadcrumb.message)
      const data = breadcrumb.data as { url?: string; from?: string; to?: string } | undefined
      if (data?.url) data.url = scrubUrl(data.url)
      if (data?.from) data.from = scrubUrl(data.from)
      if (data?.to) data.to = scrubUrl(data.to)
      return breadcrumb
    },
  })
  reporter = Sentry
}

/** Report a caught error. Does nothing when reporting is off. */
export function reportError(error: unknown): void {
  reporter?.captureException(error)
}
