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
  CreateThreadRequest,
  MessagePage,
  MessageView,
  PostMessageRequest,
  Uuid,
} from '@/types'
import {
  createThread,
  fetchMessages,
  fetchSummary,
  fetchThread,
  fetchThreads,
  markThreadRead,
  postMessage,
} from '../api/support'
import { communityAdminKeys, type SupportListParams } from '../queryKeys'

/** How often the open inbox and conversation ask for news. Paused while the tab is hidden. */
export const SUPPORT_POLL_MS = 10_000
const THREADS_PAGE_SIZE = 30

export function useSupportThreads(filters: Omit<SupportListParams, 'page' | 'size'>) {
  return useInfiniteQuery({
    queryKey: communityAdminKeys.support.list(filters),
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
    queryKey: communityAdminKeys.support.detail(id ?? ''),
    queryFn: () => fetchThread(id ?? ''),
    enabled: id !== null,
  })
}

/**
 * Polls the cheap summary every 10 seconds (and not while the tab is hidden). When anything in it
 * moved (a new reply, a read, a status change) the thread list and the open conversation are
 * refetched; otherwise a poll costs one small request.
 */
export function useSupportSummary() {
  const client = useQueryClient()
  const query = useQuery({
    queryKey: communityAdminKeys.support.summary(),
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
      void client.invalidateQueries({ queryKey: communityAdminKeys.support.lists() })
      void client.invalidateQueries({ queryKey: communityAdminKeys.support.details() })
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
 * only for what came after the last cursor and appends it.
 */
export function useThreadMessages(id: Uuid | null) {
  const client = useQueryClient()
  const key = communityAdminKeys.support.messages(id ?? '')
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
    client.invalidateQueries({ queryKey: communityAdminKeys.support.lists() }),
    client.invalidateQueries({ queryKey: communityAdminKeys.support.summary() }),
    // The dashboard shows how many replies are waiting.
    client.invalidateQueries({ queryKey: communityAdminKeys.dashboard() }),
  ])
}

export function useCreateThread() {
  const client = useQueryClient()
  return useMutation({
    mutationFn: (body: CreateThreadRequest) => createThread(body),
    onSuccess: () => refreshSupport(client),
  })
}

export function usePostMessage(id: Uuid) {
  const client = useQueryClient()
  return useMutation({
    mutationFn: (body: PostMessageRequest) => postMessage(id, body),
    onSuccess: async () => {
      // Pull the new message (and the status change a reply causes) now, not at the next poll.
      await Promise.all([
        client.invalidateQueries({ queryKey: communityAdminKeys.support.messages(id) }),
        client.invalidateQueries({ queryKey: communityAdminKeys.support.detail(id) }),
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
