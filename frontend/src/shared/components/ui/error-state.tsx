import { TriangleAlert } from 'lucide-react'
import { describeError } from '@/lib/errors'
import { cn } from '@/shared/utils/cn'
import { Button } from './button'

interface ErrorStateProps {
  title?: string
  /** Shown as given. Omit when passing `error`. */
  message?: string
  /** Any thrown value: turned into friendly text by describeError. */
  error?: unknown
  /** Adds a Try again button. */
  onRetry?: () => void
  retrying?: boolean
  className?: string
  headingLevel?: 2 | 3 | 4
}

export function ErrorState({
  title = 'We could not load this',
  message,
  error,
  onRetry,
  retrying = false,
  className,
  headingLevel = 3,
}: ErrorStateProps) {
  const text = message ?? (error !== undefined ? describeError(error) : 'Something went wrong.')
  const Heading = `h${headingLevel}` as const
  return (
    <div
      role="alert"
      className={cn(
        'flex flex-col items-center gap-3 rounded-card border border-danger/30 bg-danger-50 px-6 py-10 text-center',
        className,
      )}
    >
      <TriangleAlert aria-hidden="true" className="size-9 text-danger" strokeWidth={1.5} />
      <Heading className="font-heading text-lg font-semibold text-brand-900">{title}</Heading>
      <p className="max-w-prose text-sm text-ink">{text}</p>
      {onRetry ? (
        <Button variant="secondary" onClick={onRetry} loading={retrying} className="mt-1">
          Try again
        </Button>
      ) : null}
    </div>
  )
}
