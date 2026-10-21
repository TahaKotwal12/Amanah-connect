import type { ReactNode } from 'react'
import { cn } from '@/shared/utils/cn'
import { Breadcrumbs, type Crumb } from './breadcrumbs'

interface PageHeaderProps {
  title: string
  description?: string
  breadcrumbs?: Crumb[]
  /** Buttons for the page's main actions, aligned right on wide screens. */
  actions?: ReactNode
  className?: string
  /** Heading level. 1 for a page; higher only when shown inside other content. */
  headingLevel?: 1 | 2 | 3 | 4
}

/** The one <h1> of a page, with optional breadcrumbs above and actions beside it. */
export function PageHeader({
  title,
  description,
  breadcrumbs,
  actions,
  className,
  headingLevel = 1,
}: PageHeaderProps) {
  const Heading = `h${headingLevel}` as const
  return (
    <header className={cn('flex flex-col gap-3 pb-6', className)}>
      {breadcrumbs ? <Breadcrumbs items={breadcrumbs} /> : null}
      <div className="flex flex-wrap items-start justify-between gap-4">
        <div className="min-w-0">
          <Heading className="font-heading text-3xl font-semibold text-brand-900">{title}</Heading>
          {description ? <p className="mt-1 max-w-prose text-muted">{description}</p> : null}
        </div>
        {actions ? <div className="flex flex-wrap items-center gap-2">{actions}</div> : null}
      </div>
    </header>
  )
}
