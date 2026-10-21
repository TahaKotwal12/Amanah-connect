import { isAxiosError } from 'axios'
import type { FieldViolation, ProblemDetails } from '@/types'

/** Codes produced on the client, never by the API. */
export const CLIENT_ERROR_CODES = {
  network: 'NETWORK_ERROR',
  timeout: 'REQUEST_TIMEOUT',
  cancelled: 'REQUEST_CANCELLED',
  unknown: 'UNKNOWN_ERROR',
} as const

/**
 * Every failed API call surfaces as this. It carries the stable `code` from the RFC 9457 body
 * (see lib/errors.ts for the friendly text) and any per-field validation errors.
 */
export class ApiError extends Error {
  /** HTTP status, or 0 when no response arrived (offline, DNS, CORS, timeout). */
  readonly status: number
  /** Stable machine-readable code, e.g. INVALID_CREDENTIALS. Safe to switch on. */
  readonly code: string
  /** The server's human-readable explanation. Not guaranteed to be friendly: see lib/errors.ts. */
  readonly detail: string
  readonly title: string | undefined
  readonly fieldErrors: FieldViolation[]
  /** Rule failures that belong to no single field, one sentence each (for example password policy). */
  readonly problems: string[]
  readonly requestId: string | undefined
  /** From the Retry-After header on 429 and 423 responses. */
  readonly retryAfterSeconds: number | undefined
  /** Extra properties the server put on the problem, e.g. limit, limitValue, current and plan on PLAN_LIMIT_EXCEEDED. */
  readonly extras: Readonly<Record<string, unknown>>

  constructor(init: {
    status: number
    code: string
    detail: string
    title?: string
    fieldErrors?: FieldViolation[]
    problems?: string[]
    requestId?: string
    retryAfterSeconds?: number
    extras?: Record<string, unknown>
    cause?: unknown
  }) {
    super(init.detail, { cause: init.cause })
    this.name = 'ApiError'
    this.status = init.status
    this.code = init.code
    this.detail = init.detail
    this.title = init.title
    this.fieldErrors = init.fieldErrors ?? []
    this.problems = init.problems ?? []
    this.requestId = init.requestId
    this.retryAfterSeconds = init.retryAfterSeconds
    this.extras = init.extras ?? {}
  }

  get isNetworkError(): boolean {
    return this.status === 0
  }
  get isClientError(): boolean {
    return this.status >= 400 && this.status < 500
  }
  get isServerError(): boolean {
    return this.status >= 500
  }

  /** First message per field, for wiring into React Hook Form's setError. */
  fieldErrorMap(): Record<string, string> {
    const map: Record<string, string> = {}
    for (const violation of this.fieldErrors) {
      if (violation.field) map[violation.field] ??= violation.message
    }
    return map
  }
}

export function isApiError(value: unknown): value is ApiError {
  return value instanceof ApiError
}

function isProblem(body: unknown): body is ProblemDetails {
  return typeof body === 'object' && body !== null
}

function isViolation(entry: unknown): entry is FieldViolation {
  return (
    typeof entry === 'object' &&
    entry !== null &&
    typeof (entry as FieldViolation).message === 'string'
  )
}

function parseRetryAfter(value: unknown): number | undefined {
  if (typeof value !== 'string' && typeof value !== 'number') return undefined
  const seconds = Number(value)
  return Number.isFinite(seconds) && seconds >= 0 ? Math.ceil(seconds) : undefined
}

function codeForStatus(status: number): string {
  switch (status) {
    case 400:
      return 'BAD_REQUEST'
    case 401:
      return 'UNAUTHENTICATED'
    case 403:
      return 'FORBIDDEN'
    case 404:
      return 'NOT_FOUND'
    case 429:
      return 'RATE_LIMITED'
    default:
      return status >= 500 ? 'INTERNAL_ERROR' : CLIENT_ERROR_CODES.unknown
  }
}

const STANDARD_PROBLEM_KEYS = new Set([
  'type',
  'title',
  'status',
  'detail',
  'instance',
  'code',
  'requestId',
  'errors',
])

/** Everything on the problem body that is not part of the standard shape. */
function extrasOf(problem: object): Record<string, unknown> {
  return Object.fromEntries(
    Object.entries(problem).filter(([key]) => !STANDARD_PROBLEM_KEYS.has(key)),
  )
}

/** Turn anything thrown by an API call into an ApiError. Already-converted errors pass through. */
export function toApiError(error: unknown): ApiError {
  if (error instanceof ApiError) return error

  if (isAxiosError(error)) {
    if (error.code === 'ERR_CANCELED') {
      return new ApiError({
        status: 0,
        code: CLIENT_ERROR_CODES.cancelled,
        detail: 'The request was cancelled.',
        cause: error,
      })
    }
    const response = error.response
    if (!response) {
      const timedOut = error.code === 'ECONNABORTED' || error.code === 'ETIMEDOUT'
      return new ApiError({
        status: 0,
        code: timedOut ? CLIENT_ERROR_CODES.timeout : CLIENT_ERROR_CODES.network,
        detail: timedOut ? 'The request timed out.' : 'The server could not be reached.',
        cause: error,
      })
    }

    const body: unknown = response.data
    const problem = isProblem(body) ? body : undefined
    const headers = response.headers as Record<string, unknown> | undefined
    const listed: unknown[] = Array.isArray(problem?.errors) ? problem.errors : []
    return new ApiError({
      status: response.status,
      code: typeof problem?.code === 'string' ? problem.code : codeForStatus(response.status),
      detail:
        typeof problem?.detail === 'string' && problem.detail
          ? problem.detail
          : response.statusText || `Request failed with status ${response.status}`,
      title: typeof problem?.title === 'string' ? problem.title : undefined,
      fieldErrors: listed.filter(isViolation),
      problems: listed.filter((entry): entry is string => typeof entry === 'string'),
      requestId: typeof problem?.requestId === 'string' ? problem.requestId : undefined,
      retryAfterSeconds: parseRetryAfter(headers?.['retry-after']),
      extras: problem ? extrasOf(problem) : {},
      cause: error,
    })
  }

  return new ApiError({
    status: 0,
    code: CLIENT_ERROR_CODES.unknown,
    detail: error instanceof Error ? error.message : 'Something went wrong.',
    cause: error,
  })
}
