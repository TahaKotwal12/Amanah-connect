import { api } from '@/lib/axios'
import { toQuery } from '@/lib/query'
import type { IsoDate, ReportFormat, ReportKind, Uuid } from '@/types'

/** The URL names of the reports (ReportController: income-expense, category-breakdown, ...). */
export const REPORT_SLUGS: Record<ReportKind, string> = {
  INCOME_EXPENSE: 'income-expense',
  CATEGORY_BREAKDOWN: 'category-breakdown',
  COLLECTION: 'collection',
  MEMBER_DUES: 'member-dues',
  DEFAULTERS: 'defaulters',
  RECEIPTS_REGISTER: 'receipts-register',
}

export interface ReportRequest {
  kind: ReportKind
  format: ReportFormat
  from?: IsoDate
  to?: IsoDate
  /** Required for the member dues statement. */
  memberId?: Uuid
  /** Defaulters as of a day. */
  asOf?: IsoDate
}

export interface ReportFile {
  blob: Blob
  filename: string
}

/** The file name the server chose, from Content-Disposition. */
export function filenameFrom(header: unknown, fallback: string): string {
  if (typeof header !== 'string') return fallback
  const encoded = /filename\*=UTF-8''([^;]+)/i.exec(header)?.[1]
  if (encoded) {
    try {
      return decodeURIComponent(encoded)
    } catch {
      return fallback
    }
  }
  return /filename="?([^";]+)"?/i.exec(header)?.[1] ?? fallback
}

/**
 * Downloads a report. `onProgress` gets 0 to 100 while the file arrives (null when the server did
 * not say how big it is, so the caller can show an indeterminate bar).
 */
export async function downloadReport(
  request: ReportRequest,
  onProgress?: (percent: number | null) => void,
  signal?: AbortSignal,
): Promise<ReportFile> {
  const { kind, ...query } = request
  const response = await api.get<Blob>(`/community/reports/${REPORT_SLUGS[kind]}`, {
    params: toQuery({ ...query, format: request.format.toLowerCase() }),
    responseType: 'blob',
    ...(signal ? { signal } : {}),
    onDownloadProgress: (event) => {
      onProgress?.(event.total ? Math.round((event.loaded / event.total) * 100) : null)
    },
  })
  const extension = request.format.toLowerCase()
  return {
    blob: response.data,
    filename: filenameFrom(
      (response.headers as Record<string, unknown>)['content-disposition'],
      `${REPORT_SLUGS[kind]}.${extension}`,
    ),
  }
}
