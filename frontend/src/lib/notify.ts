import type * as Sonner from 'sonner'
import { describeError } from './errors'

/**
 * The one way to raise a toast. Sonner is loaded the first time a toast is needed, so the
 * toaster is not part of the first page load. Wording and behaviour are kept consistent here:
 * errors stay a little longer, and API failures are always turned into friendly text.
 * Pass `id` to replace a toast instead of stacking duplicates.
 */
let sonner: Promise<typeof Sonner> | undefined
const loadSonner = () => (sonner ??= import('sonner'))

interface ToastOptions {
  id?: string
  description?: string
}

export const notify = {
  success: (message: string, options?: ToastOptions): void => {
    void loadSonner().then(({ toast }) => toast.success(message, options))
  },
  info: (message: string, options?: ToastOptions): void => {
    void loadSonner().then(({ toast }) => toast.info(message, options))
  },
  warning: (message: string, options?: ToastOptions): void => {
    void loadSonner().then(({ toast }) => toast.warning(message, options))
  },
  error: (message: string, options?: ToastOptions): void => {
    void loadSonner().then(({ toast }) => toast.error(message, { duration: 8000, ...options }))
  },
  /** Friendly message for any thrown value (ApiError, network failure, anything). */
  apiError: (error: unknown, options?: ToastOptions): void => {
    notify.error(describeError(error), options)
  },
  /** Loading, then success or error, in one toast. */
  promise: <T>(
    promise: Promise<T>,
    messages: { loading: string; success: string; error?: string },
  ): void => {
    void loadSonner().then(({ toast }) =>
      toast.promise(promise, {
        loading: messages.loading,
        success: messages.success,
        error: (error: unknown) => messages.error ?? describeError(error),
      }),
    )
  },
  dismiss: (id?: string | number): void => {
    void loadSonner().then(({ toast }) => toast.dismiss(id))
  },
}
