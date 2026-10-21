import { CircleAlert, Info } from 'lucide-react'
import type { ReactNode } from 'react'
import { cn } from '@/shared/utils/cn'

interface AuthCardProps {
  title: string
  description?: ReactNode
  children: ReactNode
}

/** The white card on the right of the split screen: the page's one <h1>, then its form. */
export function AuthCard({ title, description, children }: AuthCardProps) {
  return (
    <div className="w-full max-w-md rounded-card border border-brand-100 bg-white p-6 shadow-soft sm:p-8">
      <title>{`${title} | Amanah Connect`}</title>
      <h1 className="font-heading text-3xl font-semibold text-brand-900">{title}</h1>
      {description ? <p className="mt-2 text-muted">{description}</p> : null}
      <div className="mt-6 grid gap-5">{children}</div>
    </div>
  )
}

/** A problem. Announced as soon as it appears. */
export function AuthAlert({ children, className }: { children: ReactNode; className?: string }) {
  return (
    <div
      role="alert"
      className={cn(
        'flex items-start gap-2 rounded border border-danger/30 bg-danger-50 p-3 text-sm font-medium text-danger',
        className,
      )}
    >
      <CircleAlert aria-hidden="true" className="mt-0.5 size-4 shrink-0" />
      <div className="grid gap-1">{children}</div>
    </div>
  )
}

/** Good news or a heads-up. Announced politely. */
export function AuthNotice({ children, className }: { children: ReactNode; className?: string }) {
  return (
    <div
      role="status"
      className={cn(
        'flex items-start gap-2 rounded border border-brand-200 bg-brand-50 p-3 text-sm text-brand-900',
        className,
      )}
    >
      <Info aria-hidden="true" className="mt-0.5 size-4 shrink-0 text-brand-600" />
      <div className="grid gap-1">{children}</div>
    </div>
  )
}
