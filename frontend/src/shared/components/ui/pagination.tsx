import { ChevronLeft, ChevronRight } from 'lucide-react'
import { cn } from '@/shared/utils/cn'
import { Button } from './button'
import { SimpleSelect } from './select'

interface PaginationProps {
  /** Zero-based, like the API. */
  page: number
  pageSize: number
  total: number
  onPageChange: (page: number) => void
  /** Adds a "Rows per page" picker. */
  onPageSizeChange?: (size: number) => void
  pageSizeOptions?: readonly number[]
  className?: string
  /** Name of the landmark; make it unique if a page has more than one. */
  label?: string
}

/** Page numbers to show: always first and last, a window around the current page, "gap" between. */
export function pageWindow(page: number, pageCount: number): Array<number | 'gap'> {
  if (pageCount <= 7) return Array.from({ length: pageCount }, (_, i) => i)
  const pages = new Set([0, pageCount - 1, page - 1, page, page + 1])
  const sorted = [...pages].filter((p) => p >= 0 && p < pageCount).sort((a, b) => a - b)
  const result: Array<number | 'gap'> = []
  sorted.forEach((p, index) => {
    const previous = sorted[index - 1]
    if (previous !== undefined && p - previous > 1) result.push('gap')
    result.push(p)
  })
  return result
}

export function Pagination({
  page,
  pageSize,
  total,
  onPageChange,
  onPageSizeChange,
  pageSizeOptions = [10, 20, 50, 100],
  className,
  label = 'Pagination',
}: PaginationProps) {
  const pageCount = Math.max(1, Math.ceil(total / pageSize))
  const first = total === 0 ? 0 : page * pageSize + 1
  const last = Math.min(total, (page + 1) * pageSize)

  return (
    <div className={cn('flex flex-wrap items-center justify-between gap-3 text-sm', className)}>
      <p className="text-muted" aria-live="polite">
        {total === 0 ? 'No results' : `Showing ${first} to ${last} of ${total}`}
      </p>
      <div className="flex flex-wrap items-center gap-3">
        {onPageSizeChange ? (
          <div className="flex items-center gap-2">
            <span className="text-muted" id="rows-per-page-label">
              Rows per page
            </span>
            <SimpleSelect
              aria-label="Rows per page"
              className="w-20"
              value={String(pageSize)}
              onValueChange={(value) => {
                onPageSizeChange(Number(value))
              }}
              options={pageSizeOptions.map((size) => ({
                value: String(size),
                label: String(size),
              }))}
            />
          </div>
        ) : null}
        <nav aria-label={label} className="flex items-center gap-1">
          <Button
            variant="ghost"
            size="icon"
            aria-label="Previous page"
            disabled={page <= 0}
            onClick={() => {
              onPageChange(page - 1)
            }}
          >
            <ChevronLeft aria-hidden="true" className="size-5" />
          </Button>
          <ul className="hidden items-center gap-1 sm:flex">
            {pageWindow(page, pageCount).map((entry, index) =>
              entry === 'gap' ? (
                <li key={`gap-${index}`} aria-hidden="true" className="px-1 text-muted">
                  …
                </li>
              ) : (
                <li key={entry}>
                  <Button
                    variant={entry === page ? 'primary' : 'ghost'}
                    size="icon"
                    aria-label={`Page ${entry + 1}`}
                    aria-current={entry === page ? 'page' : undefined}
                    onClick={() => {
                      onPageChange(entry)
                    }}
                  >
                    {entry + 1}
                  </Button>
                </li>
              ),
            )}
          </ul>
          <span className="px-2 text-muted sm:hidden">
            Page {page + 1} of {pageCount}
          </span>
          <Button
            variant="ghost"
            size="icon"
            aria-label="Next page"
            disabled={page >= pageCount - 1}
            onClick={() => {
              onPageChange(page + 1)
            }}
          >
            <ChevronRight aria-hidden="true" className="size-5" />
          </Button>
        </nav>
      </div>
    </div>
  )
}
