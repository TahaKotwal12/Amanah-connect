import { Paperclip } from 'lucide-react'
import { formatRelative } from '@/lib/dates'
import { Button } from '@/shared/components/ui/button'
import { EmptyState } from '@/shared/components/ui/empty-state'
import { ErrorState } from '@/shared/components/ui/error-state'
import { Skeleton } from '@/shared/components/ui/skeleton'
import { StatusBadge } from '@/shared/components/ui/status-badge'
import { Badge } from '@/shared/components/ui/badge'
import { cn } from '@/shared/utils/cn'
import type { ThreadView, Uuid } from '@/types'

interface SupportThreadListProps {
  threads: ThreadView[]
  selectedId: Uuid | null
  onSelect: (id: Uuid) => void
  isLoading: boolean
  isFetching: boolean
  error: unknown
  onRetry: () => void
  hasMore: boolean
  loadingMore: boolean
  onLoadMore: () => void
  filtering: boolean
  onClearFilters: () => void
}

const PRIORITY_WORD: Record<string, string> = {
  URGENT: 'Urgent',
  HIGH: 'High priority',
}

/** The left column of the inbox: one button per conversation, newest activity first. */
export function SupportThreadList({
  threads,
  selectedId,
  onSelect,
  isLoading,
  isFetching,
  error,
  onRetry,
  hasMore,
  loadingMore,
  onLoadMore,
  filtering,
  onClearFilters,
}: SupportThreadListProps) {
  if (error)
    return <ErrorState headingLevel={2} error={error} onRetry={onRetry} retrying={isFetching} />
  if (isLoading) {
    return (
      <div role="status" aria-label="Loading conversations" className="grid gap-2">
        <Skeleton className="h-20" />
        <Skeleton className="h-20" />
        <Skeleton className="h-20" />
      </div>
    )
  }
  if (threads.length === 0) {
    return filtering ? (
      <EmptyState
        headingLevel={2}
        title="No conversations match"
        description="Try a different filter."
        action={
          <Button variant="secondary" onClick={onClearFilters}>
            Show active conversations
          </Button>
        }
      />
    ) : (
      <EmptyState
        headingLevel={2}
        title="Nothing needs an answer"
        description="New conversations from community administrators appear here."
      />
    )
  }
  return (
    <div aria-busy={isFetching} className={cn('grid gap-2', isFetching && 'opacity-80')}>
      <ul aria-label="Conversations" className="grid gap-2">
        {threads.map((thread) => {
          const selected = thread.id === selectedId
          const priority = PRIORITY_WORD[thread.priority]
          return (
            <li key={thread.id}>
              <button
                type="button"
                onClick={() => onSelect(thread.id)}
                aria-current={selected ? 'true' : undefined}
                className={cn(
                  'grid min-h-11 w-full gap-1 rounded border p-3 text-left',
                  selected
                    ? 'border-brand-600 bg-brand-50'
                    : 'border-brand-100 bg-white hover:border-brand-500',
                )}
              >
                <span className="flex items-start justify-between gap-2">
                  <span
                    className={cn(
                      'min-w-0 truncate text-brand-900',
                      thread.unreadCount > 0 ? 'font-semibold' : 'font-medium',
                    )}
                  >
                    {thread.subject}
                  </span>
                  <span className="shrink-0 text-xs text-muted">
                    {formatRelative(thread.lastMessageAt)}
                  </span>
                </span>
                <span className="truncate text-sm text-muted">{thread.communityName}</span>
                {thread.lastMessagePreview ? (
                  <span className="truncate text-sm">{thread.lastMessagePreview}</span>
                ) : null}
                <span className="flex flex-wrap items-center gap-2 pt-1">
                  <StatusBadge status={thread.status} />
                  {priority ? (
                    <Badge variant={thread.priority === 'URGENT' ? 'danger' : 'warning'}>
                      {priority}
                    </Badge>
                  ) : null}
                  {thread.unreadCount > 0 ? (
                    <Badge variant="gold">{thread.unreadCount} unread</Badge>
                  ) : null}
                  {thread.assignedToName ? (
                    <span className="text-xs text-muted">{thread.assignedToName}</span>
                  ) : (
                    <span className="text-xs text-muted">Unassigned</span>
                  )}
                  {thread.lastMessagePreview?.startsWith('[attachment]') ? (
                    <Paperclip aria-label="Has an attachment" className="size-3.5 text-muted" />
                  ) : null}
                </span>
              </button>
            </li>
          )
        })}
      </ul>
      {hasMore ? (
        <Button variant="secondary" onClick={onLoadMore} loading={loadingMore}>
          Load more
        </Button>
      ) : null}
    </div>
  )
}
