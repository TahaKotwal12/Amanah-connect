import { api } from '@/lib/axios'
import { toQuery } from '@/lib/query'
import type {
  CategoryView,
  CreateCategoryRequest,
  CreateEntryRequest,
  EntryView,
  LedgerSummaryView,
  LedgerUploadView,
  PageResponse,
  ReverseEntryRequest,
  UpdateCategoryRequest,
  UpdateEntryRequest,
  Uuid,
} from '@/types'
import type { DateRange, LedgerListParams } from '../queryKeys'

export async function fetchEntries(params: LedgerListParams): Promise<PageResponse<EntryView>> {
  const { data } = await api.get<PageResponse<EntryView>>('/community/ledger/entries', {
    params: toQuery(params),
  })
  return data
}

/** Income, expense and net (reversals net out) for a range, by category and by month. */
export async function fetchLedgerSummary(range: DateRange): Promise<LedgerSummaryView> {
  const { data } = await api.get<LedgerSummaryView>('/community/ledger/summary', {
    params: toQuery(range),
  })
  return data
}

export async function createEntry(body: CreateEntryRequest): Promise<EntryView> {
  const { data } = await api.post<EntryView>('/community/ledger/entries', body)
  return data
}

export async function updateEntry(id: Uuid, body: UpdateEntryRequest): Promise<EntryView> {
  const { data } = await api.patch<EntryView>(`/community/ledger/entries/${id}`, body)
  return data
}

export async function reverseEntry(id: Uuid, body: ReverseEntryRequest): Promise<EntryView> {
  const { data } = await api.post<EntryView>(`/community/ledger/entries/${id}/reverse`, body)
  return data
}

export async function requestEntryAttachmentUpload(file: {
  type: string
  size: number
}): Promise<LedgerUploadView> {
  const { data } = await api.post<LedgerUploadView>('/community/ledger/attachments/upload-url', {
    contentType: file.type,
    sizeBytes: file.size,
  })
  return data
}

export async function fetchCategories(includeInactive = true): Promise<CategoryView[]> {
  const { data } = await api.get<CategoryView[]>('/community/ledger/categories', {
    params: { includeInactive },
  })
  return data
}

export async function createCategory(body: CreateCategoryRequest): Promise<CategoryView> {
  const { data } = await api.post<CategoryView>('/community/ledger/categories', body)
  return data
}

export async function updateCategory(id: Uuid, body: UpdateCategoryRequest): Promise<CategoryView> {
  const { data } = await api.patch<CategoryView>(`/community/ledger/categories/${id}`, body)
  return data
}
