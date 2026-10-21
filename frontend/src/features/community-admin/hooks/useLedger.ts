import {
  keepPreviousData,
  useMutation,
  useQuery,
  useQueryClient,
  type QueryClient,
} from '@tanstack/react-query'
import type {
  CreateCategoryRequest,
  CreateEntryRequest,
  ReverseEntryRequest,
  UpdateCategoryRequest,
  UpdateEntryRequest,
  Uuid,
} from '@/types'
import {
  createCategory,
  createEntry,
  fetchCategories,
  fetchEntries,
  fetchLedgerSummary,
  reverseEntry,
  updateCategory,
  updateEntry,
} from '../api/ledger'
import { communityAdminKeys, type DateRange, type LedgerListParams } from '../queryKeys'

export function useEntries(params: LedgerListParams) {
  return useQuery({
    queryKey: communityAdminKeys.ledger.list(params),
    queryFn: () => fetchEntries(params),
    placeholderData: keepPreviousData,
  })
}

export function useLedgerSummary(range: DateRange) {
  return useQuery({
    queryKey: communityAdminKeys.ledger.summary(range),
    queryFn: () => fetchLedgerSummary(range),
    placeholderData: keepPreviousData,
  })
}

export function useCategories() {
  return useQuery({
    queryKey: communityAdminKeys.ledger.categories(),
    queryFn: () => fetchCategories(true),
  })
}

/** The ledger feeds the dashboard's net balance, so both are refreshed together. */
function refreshLedger(client: QueryClient) {
  return Promise.all([
    client.invalidateQueries({ queryKey: communityAdminKeys.ledger.all }),
    client.invalidateQueries({ queryKey: communityAdminKeys.dashboard() }),
  ])
}

export function useCreateEntry() {
  const client = useQueryClient()
  return useMutation({
    mutationFn: (body: CreateEntryRequest) => createEntry(body),
    onSuccess: () => refreshLedger(client),
  })
}

export function useUpdateEntry() {
  const client = useQueryClient()
  return useMutation({
    mutationFn: ({ id, body }: { id: Uuid; body: UpdateEntryRequest }) => updateEntry(id, body),
    onSuccess: () => refreshLedger(client),
  })
}

export function useReverseEntry() {
  const client = useQueryClient()
  return useMutation({
    mutationFn: ({ id, body }: { id: Uuid; body: ReverseEntryRequest }) => reverseEntry(id, body),
    onSuccess: () => refreshLedger(client),
  })
}

export function useCreateCategory() {
  const client = useQueryClient()
  return useMutation({
    mutationFn: (body: CreateCategoryRequest) => createCategory(body),
    onSuccess: () => client.invalidateQueries({ queryKey: communityAdminKeys.ledger.categories() }),
  })
}

export function useUpdateCategory() {
  const client = useQueryClient()
  return useMutation({
    mutationFn: ({ id, body }: { id: Uuid; body: UpdateCategoryRequest }) =>
      updateCategory(id, body),
    onSuccess: () => client.invalidateQueries({ queryKey: communityAdminKeys.ledger.categories() }),
  })
}
