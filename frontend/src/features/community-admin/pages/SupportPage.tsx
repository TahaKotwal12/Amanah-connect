import { useSearchParams } from 'react-router'
import { Button } from '@/shared/components/ui/button'
import { EmptyState } from '@/shared/components/ui/empty-state'
import { PageHeader } from '@/shared/components/ui/page-header'
import { cn } from '@/shared/utils/cn'
import { NewThreadDialog } from '../components/NewThreadDialog'
import { SupportConversation } from '../components/SupportConversation'
import { SupportThreadList } from '../components/SupportThreadList'
import { useWriteAccess } from '../hooks/usePlan'
import { useSupportSummary, useSupportThreads } from '../hooks/useSupport'

const VIEWS = [
  { id: 'active', label: 'Active' },
  { id: 'unread', label: 'Unread' },
  { id: 'all', label: 'All' },
] as const
type ViewId = (typeof VIEWS)[number]['id']
const isView = (value: string | null): value is ViewId => VIEWS.some((v) => v.id === value)

export default function SupportPage() {
  const [params, setParams] = useSearchParams()
  const write = useWriteAccess()
  const requested = params.get('view')
  const view: ViewId = isView(requested) ? requested : 'active'
  const selected = params.get('thread')
  const adding = params.get('new') === '1'

  // Polls the summary every 10 seconds (not in a hidden tab) and refreshes what moved.
  const summary = useSupportSummary()
  const threads = useSupportThreads(
    view === 'active'
      ? { status: ['OPEN', 'WAITING', 'RESOLVED'] }
      : view === 'unread'
        ? { unread: true }
        : {},
  )
  const items = threads.data?.pages.flatMap((page) => page.items) ?? []

  function update(change: (next: URLSearchParams) => void) {
    setParams(
      (previous) => {
        const next = new URLSearchParams(previous)
        change(next)
        return next
      },
      { replace: true },
    )
  }

  const unread = summary.data?.unreadThreads ?? 0

  return (
    <>
      <PageHeader
        title="Support"
        description="Talk to the Amanah Connect team."
        actions={
          <Button
            onClick={() => update((n) => n.set('new', '1'))}
            disabled={!write.allowed}
            {...(write.reason ? { title: write.reason } : {})}
          >
            Start a conversation
          </Button>
        }
      />
      {write.reason ? <p className="-mt-3 mb-4 text-sm text-muted">{write.reason}</p> : null}

      <div className="grid gap-6 lg:grid-cols-[22rem_1fr]">
        <div className={cn('grid content-start gap-3', selected && 'hidden lg:grid')}>
          <div role="group" aria-label="Show conversations" className="flex flex-wrap gap-2">
            {VIEWS.map((v) => (
              <button
                key={v.id}
                type="button"
                aria-pressed={v.id === view}
                onClick={() =>
                  update((n) => {
                    if (v.id === 'active') n.delete('view')
                    else n.set('view', v.id)
                  })
                }
                className={cn(
                  'inline-flex min-h-11 items-center gap-2 rounded border px-3 text-sm font-medium',
                  v.id === view
                    ? 'border-brand-600 bg-brand-600 text-white'
                    : 'border-brand-200 bg-white text-brand-900 hover:border-brand-500',
                )}
              >
                {v.label}
                {v.id === 'unread' && unread > 0 ? (
                  <span className="rounded bg-gold-500 px-1.5 text-xs font-semibold text-brand-900">
                    {unread}
                  </span>
                ) : null}
              </button>
            ))}
          </div>
          <SupportThreadList
            threads={items}
            selectedId={selected}
            onSelect={(id) => update((n) => n.set('thread', id))}
            isLoading={threads.isLoading}
            isFetching={threads.isFetching}
            error={threads.error}
            onRetry={() => void threads.refetch()}
            hasMore={threads.hasNextPage}
            loadingMore={threads.isFetchingNextPage}
            onLoadMore={() => void threads.fetchNextPage()}
            view={view}
            onShowAll={() => update((n) => n.set('view', 'all'))}
            onNew={() => update((n) => n.set('new', '1'))}
          />
        </div>

        <div className={cn(!selected && 'hidden lg:block')}>
          {selected ? (
            <SupportConversation
              key={selected}
              id={selected}
              onBack={() => update((n) => n.delete('thread'))}
            />
          ) : (
            <EmptyState
              headingLevel={2}
              title="Choose a conversation"
              description="Pick one from the list to read it and reply."
            />
          )}
        </div>
      </div>

      <NewThreadDialog
        open={adding}
        onOpenChange={(open) => !open && update((n) => n.delete('new'))}
        onCreated={(id) =>
          update((n) => {
            n.delete('new')
            n.set('thread', id)
          })
        }
      />
    </>
  )
}
