import {
  keepPreviousData,
  useInfiniteQuery,
  useMutation,
  useQuery,
  useQueryClient,
  type QueryClient,
} from '@tanstack/react-query'
import { useEffect, useRef } from 'react'
import type {
  MessagePage,
  MessageView,
  PostMessageRequest,
  UpdateThreadRequest,
  Uuid,
} from '@/types'
import {
  fetchCounts,
  fetchMessages,
  fetchSummary,
  fetchThread,
  fetchThreads,
  markThreadRead,
  postMessage,
  updateThread,
} from '../api/support'
import { superadminKeys, type SupportListParams } from '../queryKeys'

/** How often the open inbox and conversation ask for news. Paused while the tab is hidden. */
export const SUPPORT_POLL_MS = 10_000
const THREADS_PAGE_SIZE = 30

export function useSupportCounts() {
  return useQuery({
    queryKey: superadminKeys.support.counts(),
    queryFn: fetchCounts,
    refetchInterval: SUPPORT_POLL_MS,
    // Default: no polling in a hidden tab. It catches up the moment the tab is shown again.
    refetchIntervalInBackground: false,
  })
}

export function useSupportThreads(filters: Omit<SupportListParams, 'page' | 'size'>) {
  return useInfiniteQuery({
    queryKey: superadminKeys.support.list(filters),
    queryFn: ({ pageParam }) =>
      fetchThreads({ ...filters, page: pageParam, size: THREADS_PAGE_SIZE }),
    initialPageParam: 0,
    getNextPageParam: (last) =>
      (last.page + 1) * last.size < last.total ? last.page + 1 : undefined,
    placeholderData: keepPreviousData,
  })
}

export function useThread(id: Uuid | null) {
  return useQuery({
    queryKey: superadminKeys.support.detail(id ?? ''),
    queryFn: () => fetchThread(id ?? ''),
    enabled: id !== null,
  })
}

/**
 * Polls the cheap summary. When anything in it moved (a new message anywhere, a thread read, a
 * status change), the thread list, counts and the open thread are refetched; otherwise a poll
 * costs one small request.
 */
export function useSupportSummary() {
  const client = useQueryClient()
  const query = useQuery({
    queryKey: superadminKeys.support.summary(),
    queryFn: fetchSummary,
    refetchInterval: SUPPORT_POLL_MS,
    refetchIntervalInBackground: false,
  })
  const signature = useRef<string | null>(null)
  const data = query.data
  useEffect(() => {
    if (!data) return
    const next = JSON.stringify(
      data.threads.map((t) => [t.id, t.messageSeq, t.status, t.unreadCount]),
    )
    if (signature.current !== null && signature.current !== next) {
      void client.invalidateQueries({ queryKey: superadminKeys.support.lists() })
      void client.invalidateQueries({ queryKey: superadminKeys.support.counts() })
      void client.invalidateQueries({
        queryKey: superadminKeys.support.details(),
        exact: false,
      })
    }
    signature.current = next
  }, [data, client])
  return query
}

interface ThreadMessages {
  items: MessageView[]
  cursor: number
  thread: MessagePage['thread'] | null
}

const EMPTY: ThreadMessages = { items: [], cursor: 0, thread: null }

/**
 * Every message of a thread, oldest first. The first load pages from the start; every poll asks
 * only for what came after the last cursor and appends it, so a quiet thread costs a tiny request.
 */
export function useThreadMessages(id: Uuid | null) {
  const client = useQueryClient()
  const key = superadminKeys.support.messages(id ?? '')
  return useQuery({
    queryKey: key,
    enabled: id !== null,
    refetchInterval: SUPPORT_POLL_MS,
    refetchIntervalInBackground: false,
    queryFn: async (): Promise<ThreadMessages> => {
      const previous = client.getQueryData<ThreadMessages>(key) ?? EMPTY
      const items = [...previous.items]
      let cursor = previous.cursor
      let thread = previous.thread
      for (;;) {
        const page = await fetchMessages(id ?? '', cursor)
        const known = new Set(items.map((message) => message.id))
        items.push(...page.items.filter((message) => !known.has(message.id)))
        cursor = page.cursor
        thread = page.thread
        if (!page.hasMore) break
      }
      return { items, cursor, thread }
    },
  })
}

function refreshSupport(client: QueryClient) {
  return Promise.all([
    client.invalidateQueries({ queryKey: superadminKeys.support.lists() }),
    client.invalidateQueries({ queryKey: superadminKeys.support.counts() }),
    client.invalidateQueries({ queryKey: superadminKeys.support.summary() }),
  ])
}

/** Assign, change priority, resolve, close, reopen. Not optimistic: the server owns the rules. */
export function useUpdateThread() {
  const client = useQueryClient()
  return useMutation({
    mutationFn: ({ id, body }: { id: Uuid; body: UpdateThreadRequest }) => updateThread(id, body),
    onSuccess: async (thread) => {
      client.setQueryData(superadminKeys.support.detail(thread.id), thread)
      await refreshSupport(client)
    },
  })
}

export function usePostMessage(id: Uuid) {
  const client = useQueryClient()
  return useMutation({
    mutationFn: (body: PostMessageRequest) => postMessage(id, body),
    onSuccess: async () => {
      // Pull the new message (and the status change a reply causes) now, not at the next poll.
      await Promise.all([
        client.invalidateQueries({ queryKey: superadminKeys.support.messages(id) }),
        client.invalidateQueries({ queryKey: superadminKeys.support.detail(id) }),
        refreshSupport(client),
      ])
    },
  })
}

export function useMarkThreadRead() {
  const client = useQueryClient()
  return useMutation({
    mutationFn: ({ id, upToSeq }: { id: Uuid; upToSeq?: number }) => markThreadRead(id, upToSeq),
    onSuccess: () => refreshSupport(client),
  })
}
