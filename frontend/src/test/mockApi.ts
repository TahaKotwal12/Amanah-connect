import { AxiosError, type AxiosResponse, type InternalAxiosRequestConfig } from 'axios'
import { api, rawApi } from '@/lib/axios'

export interface MockReply {
  status: number
  data?: unknown
  headers?: Record<string, string>
  /** Hold the response back, so tests can make requests overlap. */
  delayMs?: number
}

export type MockHandler = (config: InternalAxiosRequestConfig) => MockReply | Promise<MockReply>

export interface RecordedCall {
  method: string
  url: string
  authorization: string | undefined
  requestedWith: string | undefined
}

/**
 * Replace the network under both axios instances with handlers keyed "METHOD /path" (the path is
 * relative to /api/v1). Interceptors, error parsing and retry logic still run for real. Unmatched
 * calls fail loudly so a test cannot pass by accident.
 */
export function installMockApi(handlers: Record<string, MockHandler>) {
  const calls: RecordedCall[] = []
  const originalApi = api.defaults.adapter
  const originalRaw = rawApi.defaults.adapter

  const adapter = async (config: InternalAxiosRequestConfig): Promise<AxiosResponse> => {
    const method = (config.method ?? 'get').toUpperCase()
    const url = config.url ?? ''
    calls.push({
      method,
      url,
      authorization: config.headers.get('Authorization') as string | undefined,
      requestedWith: config.headers.get('X-Requested-With') as string | undefined,
    })

    const handler = handlers[`${method} ${url}`]
    if (!handler) throw new Error(`No mock for ${method} ${url}`)
    const reply = await handler(config)
    if (reply.delayMs) await new Promise((resolve) => setTimeout(resolve, reply.delayMs))

    const response: AxiosResponse = {
      data: reply.data,
      status: reply.status,
      statusText: '',
      headers: reply.headers ?? {},
      config,
    }
    if (reply.status >= 200 && reply.status < 300) return response
    throw new AxiosError(
      `Request failed with status code ${reply.status}`,
      reply.status >= 500 ? AxiosError.ERR_BAD_RESPONSE : AxiosError.ERR_BAD_REQUEST,
      config,
      null,
      response,
    )
  }

  api.defaults.adapter = adapter
  rawApi.defaults.adapter = adapter

  return {
    calls,
    count: (key: string) => calls.filter((call) => `${call.method} ${call.url}` === key).length,
    restore: () => {
      api.defaults.adapter = originalApi
      rawApi.defaults.adapter = originalRaw
    },
  }
}

/** A problem+json reply as the backend sends it. */
export function problem(
  status: number,
  code: string,
  detail = code,
  extra: Record<string, unknown> = {},
  headers?: Record<string, string>,
): MockReply {
  const reply: MockReply = {
    status,
    data: {
      type: `urn:amanah:problem:${code.toLowerCase()}`,
      title: code,
      status,
      detail,
      code,
      ...extra,
    },
  }
  if (headers) reply.headers = headers
  return reply
}
