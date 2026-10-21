import { api } from '@/lib/axios'
import { toQuery } from '@/lib/query'
import type { InvoiceView, PageResponse } from '@/types'

/** The invoices furthest past due first: the defaulters the dashboard points at. */
export async function fetchOverdueInvoices(size = 5): Promise<PageResponse<InvoiceView>> {
  const { data } = await api.get<PageResponse<InvoiceView>>('/community/invoices', {
    params: toQuery({ status: 'OVERDUE', size, sort: ['dueDate,asc'] }),
  })
  return data
}
