import { keepPreviousData, useInfiniteQuery } from '@tanstack/react-query'
import { fetchAudit } from '../api/audit'
import { superadminKeys, type AuditParams } from '../queryKeys'

const PAGE_SIZE = 50

/** Newest first, then older pages by keyset cursor ("Load more"): it never skips or repeats a row. */
export function useAuditTrail(filters: Omit<AuditParams, 'cursor' | 'limit'>) {
  return useInfiniteQuery({
    queryKey: superadminKeys.audit.list(filters),
    queryFn: ({ pageParam }) =>
      fetchAudit({ ...filters, limit: PAGE_SIZE, ...(pageParam ? { cursor: pageParam } : {}) }),
    initialPageParam: '',
    getNextPageParam: (last) => (last.hasMore ? last.nextCursor : undefined),
    placeholderData: keepPreviousData,
  })
}
