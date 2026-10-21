import { MutationCache, QueryCache, QueryClient } from '@tanstack/react-query'
import { isApiError, toApiError } from './apiError'
import { noticeFromError, usePlanLimitStore } from '@/store/planLimitStore'
import { notify } from './notify'

declare module '@tanstack/react-query' {
  interface Register {
    defaultError: Error
    queryMeta: {
      /** Skip the global error toast: the screen shows its own error state. */
      silent?: boolean
    }
    mutationMeta: {
      silent?: boolean
    }
  }
}

/** 4xx means the request itself is wrong or not allowed: retrying cannot change the answer. */
export function shouldRetry(failureCount: number, error: unknown): boolean {
  const apiError = isApiError(error) ? error : toApiError(error)
  if (apiError.isClientError) return false
  return failureCount < 2
}

/** One toast per distinct problem, however many queries fail at once. */
function toastOnce(error: unknown): void {
  const apiError = toApiError(error)
  if (apiError.code === 'REQUEST_CANCELLED') return
  // 401 is handled by the session machinery (refresh or redirect to /login).
  if (apiError.status === 401) return
  notify.apiError(apiError, { id: `api-error:${apiError.code}` })
}

/** The app's client. A function so tests can build one with the same error handling. */
export function createQueryClient(): QueryClient {
  return new QueryClient({
    queryCache: new QueryCache({
      onError: (error, query) => {
        if (query.meta?.silent) return
        // A failed first load renders its own ErrorState. Toast only when a refetch fails behind
        // data that is already on screen, which would otherwise go stale without a word.
        if (query.state.data !== undefined) toastOnce(error)
      },
    }),
    mutationCache: new MutationCache({
      onError: (error, _variables, _context, mutation) => {
        if (mutation.meta?.silent) return
        // A plan limit is not an error to toast: it gets its own explanation and a way forward.
        const apiError = toApiError(error)
        if (apiError.code === 'PLAN_LIMIT_EXCEEDED') {
          usePlanLimitStore.getState().show(noticeFromError(apiError))
          return
        }
        // Field-level validation errors belong on the form, not in a toast.
        if (toApiError(error).fieldErrors.length > 0) return
        toastOnce(error)
      },
    }),
    defaultOptions: {
      queries: {
        staleTime: 60_000,
        gcTime: 5 * 60_000,
        retry: shouldRetry,
        refetchOnWindowFocus: false,
      },
      mutations: {
        // Money and state changes are never retried automatically: use an Idempotency-Key and let
        // the user press the button again.
        retry: false,
      },
    },
  })
}

export const queryClient = createQueryClient()
