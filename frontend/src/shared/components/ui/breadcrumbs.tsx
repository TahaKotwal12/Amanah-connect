import { ChevronRight } from 'lucide-react'
import { Link } from 'react-router'
import { cn } from '@/shared/utils/cn'

export interface Crumb {
  label: string
  /** Omit on the current page. */
  to?: string
}

export function Breadcrumbs({
  items,
  className,
  label = 'Breadcrumb',
}: {
  items: Crumb[]
  className?: string
  /** Name of the landmark; make it unique if a page has more than one trail. */
  label?: string
}) {
  return (
    <nav aria-label={label} className={className}>
      <ol className="flex flex-wrap items-center gap-1.5 text-sm text-muted">
        {items.map((item, index) => {
          const isLast = index === items.length - 1
          return (
            <li key={`${item.label}-${index}`} className="flex items-center gap-1.5">
              {item.to && !isLast ? (
                <Link to={item.to} className="rounded text-brand-700 hover:underline">
                  {item.label}
                </Link>
              ) : (
                <span
                  aria-current={isLast ? 'page' : undefined}
                  className={cn(isLast && 'font-medium text-ink')}
                >
                  {item.label}
                </span>
              )}
              {isLast ? null : <ChevronRight aria-hidden="true" className="size-3.5" />}
            </li>
          )
        })}
      </ol>
    </nav>
  )
}
