import { cn } from '@/shared/utils/cn'

interface ErrorFallbackProps {
  title?: string
  message: string
  /** Label and handler for the primary action. Defaults to reloading the page. */
  actionLabel?: string
  onAction?: () => void
  fullScreen?: boolean
}

/** Last-resort error screen. It must not depend on router, query or auth state: any of them may be what broke. */
export function ErrorFallback({
  title = 'Something went wrong',
  message,
  actionLabel = 'Reload the page',
  onAction = () => {
    window.location.reload()
  },
  fullScreen = true,
}: ErrorFallbackProps) {
  return (
    <div
      role="alert"
      className={cn(
        'flex flex-col items-center justify-center gap-4 bg-ivory px-6 py-16 text-center',
        fullScreen && 'min-h-screen',
      )}
    >
      <p className="eyebrow">Amanah Connect</p>
      <h1 className="text-3xl">{title}</h1>
      <p className="max-w-prose text-muted">{message}</p>
      <div className="flex flex-wrap justify-center gap-3">
        <button
          type="button"
          onClick={onAction}
          className="min-h-11 rounded bg-brand-600 px-5 py-2 text-sm font-semibold text-white hover:bg-brand-700"
        >
          {actionLabel}
        </button>
        <a
          href="/"
          className="inline-flex min-h-11 items-center rounded border border-brand-600 px-5 py-2 text-sm font-semibold text-brand-700 hover:bg-brand-50"
        >
          Go to the home page
        </a>
      </div>
    </div>
  )
}
