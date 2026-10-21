import { useMemo } from 'react'
import { useSearchParams } from 'react-router'
import { PageHeader } from '@/shared/components/ui/page-header'
import { SearchInput } from '@/shared/components/ui/search-input'
import { cn } from '@/shared/utils/cn'
import type { PlatformSupportCounts, ThreadStatus } from '@/types'
import { SupportConversation } from '../components/SupportConversation'
import { SupportThreadList } from '../components/SupportThreadList'
import { useSupportCounts, useSupportSummary, useSupportThreads } from '../hooks/useSupport'
import type { SupportListParams } from '../queryKeys'

const ACTIVE: ThreadStatus[] = ['OPEN', 'WAITING']

type Preset = 'active' | 'unread' | 'unassigned' | 'mine' | 'urgent' | 'resolved' | 'closed'

const PRESETS: ReadonlyArray<{
  id: Preset
  label: string
  count: (counts: PlatformSupportCounts) => number | null
  filters: Omit<SupportListParams, 'page' | 'size' | 'q' | 'communityId'>
}> = [
  { id: 'active', label: 'Active', count: (c) => c.open + c.waiting, filters: { status: ACTIVE } },
  { id: 'unread', label: 'Unread', count: (c) => c.unreadThreads, filters: { unread: true } },
  {
    id: 'unassigned',
    label: 'Unassigned',
    count: (c) => c.activeUnassigned,
    filters: { unassigned: true, status: ACTIVE },
  },
  { id: 'mine', label: 'Mine', count: () => null, filters: { mine: true, status: ACTIVE } },
  {
    id: 'urgent',
    label: 'Urgent',
    count: (c) => c.urgentActive,
    filters: { priority: 'URGENT', status: ACTIVE },
  },
  {
    id: 'resolved',
    label: 'Resolved',
    count: (c) => c.resolved,
    filters: { status: ['RESOLVED'] },
  },
  { id: 'closed', label: 'Closed', count: (c) => c.closed, filters: { status: ['CLOSED'] } },
]

function isPreset(value: string | null): value is Preset {
  return PRESETS.some((preset) => preset.id === value)
}

export default function SupportPage() {
  const [params, setParams] = useSearchParams()
  const requested = params.get('filter')
  const preset: Preset = isPreset(requested) ? requested : 'active'
  const threadId = params.get('thread')
  const communityId = params.get('communityId') ?? ''
  const q = params.get('q') ?? ''

  function setParam(key: string, value: string | null) {
    setParams(
      (previous) => {
        const next = new URLSearchParams(previous)
        if (value) next.set(key, value)
        else next.delete(key)
        return next
      },
      { replace: true },
    )
  }

  const filters = useMemo(
    () => ({
      ...PRESETS.find((item) => item.id === preset)?.filters,
      ...(communityId ? { communityId } : {}),
      ...(q ? { q } : {}),
    }),
    [preset, communityId, q],
  )
  const threads = useSupportThreads(filters)
  const counts = useSupportCounts()
  useSupportSummary() // polls; moves the list, counts and open conversation when anything changes
  const rows = threads.data?.pages.flatMap((page) => page.items) ?? []
  const filtering = preset !== 'active' || communityId !== '' || q !== ''

  return (
    <>
      <PageHeader
        title="Support inbox"
        description="Conversations with community administrators. Updates every 10 seconds while this tab is open."
      />

      <div
        className="mb-4 flex flex-wrap items-center gap-2"
        role="group"
        aria-label="Filter conversations"
      >
        {PRESETS.map((item) => {
          const total = counts.data ? item.count(counts.data) : null
          const selected = item.id === preset
          return (
            <button
              key={item.id}
              type="button"
              aria-pressed={selected}
              onClick={() => {
                setParam('filter', item.id === 'active' ? null : item.id)
              }}
              className={cn(
                'inline-flex min-h-11 items-center gap-2 rounded border px-3 text-sm font-medium',
                selected
                  ? 'border-brand-600 bg-brand-600 text-white'
                  : 'border-brand-200 bg-white text-brand-900 hover:border-brand-500',
              )}
            >
              {item.label}
              {total !== null ? (
                <span
                  className={cn(
                    'rounded px-1.5 text-xs tabular-nums',
                    selected ? 'bg-white/20' : 'bg-brand-50 text-muted',
                  )}
                >
                  <span className="sr-only">, </span>
                  {total}
                </span>
              ) : null}
            </button>
          )
        })}
        <SearchInput
          value={q}
          onSearch={(value) => setParam('q', value || null)}
          label="Search conversations"
          placeholder="Search subject or community"
          className="ml-auto w-full sm:w-72"
        />
      </div>

      <div className="grid gap-6 lg:grid-cols-[22rem_1fr]">
        <div className={cn(threadId ? 'hidden lg:block' : '')}>
          <SupportThreadList
            threads={rows}
            selectedId={threadId}
            onSelect={(id) => setParam('thread', id)}
            isLoading={threads.isLoading}
            isFetching={threads.isFetching}
            error={threads.error}
            onRetry={() => void threads.refetch()}
            hasMore={threads.hasNextPage}
            loadingMore={threads.isFetchingNextPage}
            onLoadMore={() => void threads.fetchNextPage()}
            filtering={filtering}
            onClearFilters={() => setParams(new URLSearchParams(), { replace: true })}
          />
        </div>
        <div className={cn(!threadId ? 'hidden lg:block' : '')}>
          {threadId ? (
            <SupportConversation
              key={threadId}
              threadId={threadId}
              onBack={() => setParam('thread', null)}
            />
          ) : (
            <p className="rounded-card border border-dashed border-brand-200 p-8 text-center text-muted">
              Choose a conversation to read and reply.
            </p>
          )}
        </div>
      </div>
    </>
  )
}
