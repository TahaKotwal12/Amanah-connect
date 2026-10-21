import { AxiosError, type AxiosResponse, type InternalAxiosRequestConfig } from 'axios'
import { ApiError, CLIENT_ERROR_CODES, isApiError, toApiError } from './apiError'

function axiosError(status: number, data: unknown, headers: Record<string, string> = {}) {
  const config = { headers: {} } as InternalAxiosRequestConfig
  const response = { data, status, statusText: 'Status', headers, config } as AxiosResponse
  return new AxiosError('failed', 'ERR_BAD_REQUEST', config, null, response)
}

describe('toApiError', () => {
  it('parses an RFC 9457 problem with a code', () => {
    const error = toApiError(
      axiosError(401, {
        type: 'urn:amanah:problem:invalid-credentials',
        title: 'Invalid credentials',
        status: 401,
        detail: 'Email or password is incorrect.',
        code: 'INVALID_CREDENTIALS',
        requestId: 'req-1',
      }),
    )
    expect(error).toBeInstanceOf(ApiError)
    expect(error.status).toBe(401)
    expect(error.code).toBe('INVALID_CREDENTIALS')
    expect(error.detail).toBe('Email or password is incorrect.')
    expect(error.title).toBe('Invalid credentials')
    expect(error.requestId).toBe('req-1')
    expect(error.isClientError).toBe(true)
    expect(error.fieldErrors).toEqual([])
  })

  it('carries field errors from VALIDATION_FAILED', () => {
    const error = toApiError(
      axiosError(400, {
        status: 400,
        detail: 'One or more fields are invalid.',
        code: 'VALIDATION_FAILED',
        errors: [
          { field: 'email', message: 'must be a well-formed email address' },
          { field: 'email', message: 'second message for the same field' },
          { field: 'fullName', message: 'must not be blank' },
        ],
      }),
    )
    expect(error.code).toBe('VALIDATION_FAILED')
    expect(error.fieldErrors).toHaveLength(3)
    // The first message per field wins, for React Hook Form's setError.
    expect(error.fieldErrorMap()).toEqual({
      email: 'must be a well-formed email address',
      fullName: 'must not be blank',
    })
  })

  it('reads Retry-After on a rate limit', () => {
    const error = toApiError(
      axiosError(429, { code: 'RATE_LIMITED', detail: 'Slow down' }, { 'retry-after': '42' }),
    )
    expect(error.status).toBe(429)
    expect(error.retryAfterSeconds).toBe(42)
  })

  it('ignores a malformed Retry-After', () => {
    const error = toApiError(axiosError(429, { code: 'RATE_LIMITED' }, { 'retry-after': 'soon' }))
    expect(error.retryAfterSeconds).toBeUndefined()
  })

  it('derives a code from the status when the body is not a problem document', () => {
    expect(toApiError(axiosError(404, '<html>Not Found</html>')).code).toBe('NOT_FOUND')
    expect(toApiError(axiosError(502, '')).code).toBe('INTERNAL_ERROR')
    expect(toApiError(axiosError(502, '')).isServerError).toBe(true)
    expect(toApiError(axiosError(418, null)).code).toBe(CLIENT_ERROR_CODES.unknown)
  })

  it('reports a missing response as a network error', () => {
    const error = toApiError(new AxiosError('Network Error', 'ERR_NETWORK'))
    expect(error.status).toBe(0)
    expect(error.code).toBe(CLIENT_ERROR_CODES.network)
    expect(error.isNetworkError).toBe(true)
  })

  it('reports a timeout and a cancellation', () => {
    expect(toApiError(new AxiosError('timeout', 'ECONNABORTED')).code).toBe(
      CLIENT_ERROR_CODES.timeout,
    )
    expect(toApiError(new AxiosError('canceled', 'ERR_CANCELED')).code).toBe(
      CLIENT_ERROR_CODES.cancelled,
    )
  })

  it('wraps unknown throwables and passes ApiErrors through untouched', () => {
    expect(toApiError(new Error('boom')).detail).toBe('boom')
    expect(toApiError('weird').code).toBe(CLIENT_ERROR_CODES.unknown)
    const existing = new ApiError({ status: 409, code: 'SLUG_TAKEN', detail: 'taken' })
    expect(toApiError(existing)).toBe(existing)
    expect(isApiError(existing)).toBe(true)
    expect(isApiError(new Error('x'))).toBe(false)
  })
})
