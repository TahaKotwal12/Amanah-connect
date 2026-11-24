/**
 * Removes personal data from an error report before it leaves the browser. Pure functions, so the
 * rules are tested (scrub.test.ts) and do not depend on the reporting library.
 *
 * What a report may keep: the error type and message, the stack, the route pattern, the release,
 * the HTTP status and our stable error code. What it must not: who the person is, their cookies and
 * tokens, anything they typed, member emails or phone numbers, and query strings (reset and invite
 * links carry tokens there).
 */

const EMAIL = /[A-Z0-9._%+-]+@[A-Z0-9.-]+\.[A-Z]{2,}/gi
const BEARER = /Bearer\s+[A-Za-z0-9._~+/=-]+/gi
const JWT = /\beyJ[A-Za-z0-9_-]{8,}\.[A-Za-z0-9_-]{8,}\.[A-Za-z0-9_-]*/g
// Phone numbers: 8 to 15 digits, optionally with +, spaces, dashes or brackets.
const PHONE = /(?<![\w.])\+?\d[\d\s().-]{6,}\d(?![\w.])/g
// Tokens in the path of the member pages (/join/<token>, /pay/<token>).
const PATH_TOKEN = /\/(join|pay)\/[^/?#\s]+/g

export function scrubText(text: string): string {
  return text
    .replace(BEARER, 'Bearer [token]')
    .replace(JWT, '[token]')
    .replace(EMAIL, '[email]')
    .replace(PATH_TOKEN, '/$1/[token]')
    .replace(PHONE, (match) => (match.replace(/\D/g, '').length >= 8 ? '[phone]' : match))
}

/** A URL with no query string, no fragment and no tokens in the path. */
export function scrubUrl(url: string): string {
  const withoutQuery = url.split(/[?#]/)[0] ?? ''
  return scrubText(withoutQuery)
}

type Json = Record<string, unknown>

const DROP_KEYS = new Set([
  'user',
  'cookies',
  'cookie',
  'authorization',
  'headers',
  'data',
  'query_string',
  'request_body',
])

function scrubValue(value: unknown): unknown {
  if (typeof value === 'string') return scrubText(value)
  if (Array.isArray(value)) return value.map(scrubValue)
  if (value && typeof value === 'object') return scrubObject(value as Json)
  return value
}

function scrubObject(object: Json): Json {
  const out: Json = {}
  for (const [key, value] of Object.entries(object)) {
    if (DROP_KEYS.has(key.toLowerCase())) continue
    out[key] = scrubValue(value)
  }
  return out
}

/**
 * Applied as Sentry's `beforeSend` / `beforeBreadcrumb`. Typed loosely on purpose so this file
 * does not import the SDK (the SDK is loaded only when reporting is switched on).
 */
export function scrubEvent<T extends Json>(event: T): T {
  const copy = scrubObject(event) as T
  const request = (event as { request?: { url?: string } }).request
  if (request?.url) (copy as Json).request = { url: scrubUrl(request.url) }
  return copy
}
