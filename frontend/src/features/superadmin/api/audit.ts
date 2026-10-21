import { api } from '@/lib/axios'
import type { AdminAuditPage } from '@/types'
import type { AuditParams } from '../queryKeys'
import { toQuery } from './params'

export async function fetchAudit(params: AuditParams): Promise<AdminAuditPage> {
  const { data } = await api.get<AdminAuditPage>('/admin/audit', { params: toQuery(params) })
  return data
}

/** The same filters as the list, as a CSV download (at most 100,000 rows, newest first). */
export async function exportAudit(
  params: Omit<AuditParams, 'cursor' | 'limit'>,
): Promise<{ blob: Blob; rows: number | null; truncated: boolean }> {
  const response = await api.get<Blob>('/admin/audit/export', {
    params: toQuery(params),
    responseType: 'blob',
  })
  const rows = Number(response.headers['x-export-rows'])
  return {
    blob: response.data,
    rows: Number.isFinite(rows) ? rows : null,
    truncated: response.headers['x-export-truncated'] === 'true',
  }
}
