import { ApiError } from './apiError'
import { describeError, ERROR_MESSAGES, formatWait } from './errors'

// The backend source, when it is checked out next to the frontend (a frontend-only checkout
// matches nothing, and the drift guard below skips).
const errorCodeSources = import.meta.glob<string>(
  '../../../backend/src/main/java/com/amanahconnect/common/error/ErrorCode.java',
  { query: '?raw', import: 'default', eager: true },
)
const errorCodeSource = Object.values(errorCodeSources)[0]

describe('describeError', () => {
  it('gives friendly text for a known code, not the server detail', () => {
    const error = new ApiError({
      status: 401,
      code: 'INVALID_CREDENTIALS',
      detail: 'bad creds for user 42',
    })
    expect(describeError(error)).toBe(ERROR_MESSAGES.INVALID_CREDENTIALS)
    expect(describeError(error)).not.toContain('42')
  })

  it('adds the wait time to rate limits and lockouts', () => {
    const limited = new ApiError({
      status: 429,
      code: 'RATE_LIMITED',
      detail: '',
      retryAfterSeconds: 90,
    })
    expect(describeError(limited)).toContain('try again in 2 minutes')

    const locked = new ApiError({
      status: 423,
      code: 'ACCOUNT_LOCKED',
      detail: '',
      retryAfterSeconds: 30,
    })
    expect(describeError(locked)).toContain('try again in 30 seconds')
  })

  it('keeps the server detail where it carries the useful part', () => {
    const error = new ApiError({
      status: 402,
      code: 'PLAN_LIMIT_EXCEEDED',
      detail: 'Your Starter plan allows 50 members.',
    })
    expect(describeError(error)).toBe('Your Starter plan allows 50 members.')
  })

  it('falls back sensibly for codes it has never seen', () => {
    expect(describeError(new ApiError({ status: 500, code: 'SOMETHING_NEW', detail: 'x' }))).toBe(
      ERROR_MESSAGES.INTERNAL_ERROR,
    )
    expect(describeError(new ApiError({ status: 409, code: 'SOMETHING_NEW', detail: 'x' }))).toBe(
      ERROR_MESSAGES.UNKNOWN_ERROR,
    )
  })

  it('explains an unreachable server and any non-API throwable', () => {
    expect(describeError(new ApiError({ status: 0, code: 'NETWORK_ERROR', detail: '' }))).toMatch(
      /could not reach the server/i,
    )
    expect(describeError(new Error('TypeError: x is undefined'))).toBe(ERROR_MESSAGES.UNKNOWN_ERROR)
  })

  it('formats waits', () => {
    expect(formatWait(1)).toBe('1 second')
    expect(formatWait(59)).toBe('59 seconds')
    expect(formatWait(60)).toBe('1 minute')
    expect(formatWait(61)).toBe('2 minutes')
  })
})

// Drift guard: every code the backend can send must have friendly copy here. Skipped when the
// backend source is not checked out next to the frontend (for example in a frontend-only image).
describe.skipIf(errorCodeSource === undefined)('backend error codes', () => {
  it('all have a friendly message', () => {
    const codes = [
      ...(errorCodeSource ?? '').matchAll(/^\s{4}([A-Z][A-Z0-9_]+)\(HttpStatus\./gm),
    ].map((m) => m[1])
    expect(codes.length).toBeGreaterThan(50)
    const missing = codes.filter((code): code is string => !!code && !(code in ERROR_MESSAGES))
    expect(missing).toEqual([])
  })
})
