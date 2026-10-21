import { Inbox } from 'lucide-react'
import type { ReactNode } from 'react'
import { cn } from '@/shared/utils/cn'

interface EmptyStateProps {
  title: string
  description?: string
  /** Illustration slot. Defaults to a quiet icon; pass an <img alt=""> or SVG for richer art. */
  illustration?: ReactNode
  /** The next step: usually a primary Button. */
  action?: ReactNode
  /** Heading level for the title; pick one that fits the surrounding outline. */
  headingLevel?: 2 | 3 | 4
  className?: string
}

export function EmptyState({
  title,
  description,
  illustration,
  action,
  headingLevel = 3,
  className,
}: EmptyStateProps) {
  const Heading = `h${headingLevel}` as const
  return (
    <div
      className={cn(
        'flex flex-col items-center gap-3 rounded-card border border-dashed border-brand-200 bg-white px-6 py-12 text-center',
        className,
      )}
    >
      <div aria-hidden="true" className="text-brand-500">
        {illustration ?? <Inbox className="size-10" strokeWidth={1.5} />}
      </div>
      <Heading className="font-heading text-lg font-semibold text-brand-900">{title}</Heading>
      {description ? <p className="max-w-prose text-sm text-muted">{description}</p> : null}
      {action ? <div className="mt-2">{action}</div> : null}
    </div>
  )
}
