import { cn } from '@/shared/utils/cn'

interface PageSpinnerProps {
  /** Text announced to screen readers. */
  label?: string
  /** Fill the viewport (initial load) instead of the surrounding block. */
  fullScreen?: boolean
}

/** Centered loading indicator for route chunks and session bootstrap. */
export function PageSpinner({ label = 'Loading', fullScreen = false }: PageSpinnerProps) {
  return (
    <div
      role="status"
      aria-live="polite"
      className={cn(
        'flex items-center justify-center p-12',
        fullScreen ? 'min-h-screen bg-ivory' : 'min-h-[40vh]',
      )}
    >
      <span
        aria-hidden="true"
        className="size-9 animate-spin rounded-full border-[3px] border-brand-100 border-t-brand-600"
      />
      <span className="sr-only">{label}</span>
    </div>
  )
}
