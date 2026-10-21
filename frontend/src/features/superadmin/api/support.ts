import { api } from '@/lib/axios'
import type {
  SupportUploadView,
  MessagePage,
  MessageView,
  PageResponse,
  PlatformSupportCounts,
  PostMessageRequest,
  SupportSummary,
  ThreadView,
  UpdateThreadRequest,
  Uuid,
} from '@/types'
import type { SupportListParams } from '../queryKeys'
import { toQuery } from './params'

export async function fetchThreads(params: SupportListParams): Promise<PageResponse<ThreadView>> {
  const { data } = await api.get<PageResponse<ThreadView>>('/admin/support/threads', {
    params: toQuery(params),
  })
  return data
}

export async function fetchThread(id: Uuid): Promise<ThreadView> {
  const { data } = await api.get<ThreadView>(`/admin/support/threads/${id}`)
  return data
}

export async function updateThread(id: Uuid, body: UpdateThreadRequest): Promise<ThreadView> {
  const { data } = await api.patch<ThreadView>(`/admin/support/threads/${id}`, body)
  return data
}

export async function fetchCounts(): Promise<PlatformSupportCounts> {
  const { data } = await api.get<PlatformSupportCounts>('/admin/support/counts')
  return data
}

/** The cheap thing to poll: unread numbers and where each thread is up to. */
export async function fetchSummary(): Promise<SupportSummary> {
  const { data } = await api.get<SupportSummary>('/admin/support/summary')
  return data
}

/** Messages after a cursor, oldest first. Reading them does not mark anything read. */
export async function fetchMessages(id: Uuid, after: number): Promise<MessagePage> {
  const { data } = await api.get<MessagePage>(`/admin/support/threads/${id}/messages`, {
    params: { after, limit: 100 },
  })
  return data
}

export async function postMessage(id: Uuid, body: PostMessageRequest): Promise<MessageView> {
  const { data } = await api.post<MessageView>(`/admin/support/threads/${id}/messages`, body)
  return data
}

export async function markThreadRead(id: Uuid, upToSeq?: number): Promise<SupportSummary> {
  const { data } = await api.post<SupportSummary>(
    `/admin/support/threads/${id}/read`,
    upToSeq === undefined ? {} : { upToSeq },
  )
  return data
}

export async function requestAttachmentUpload(
  id: Uuid,
  file: { type: string; size: number },
): Promise<SupportUploadView> {
  const { data } = await api.post<SupportUploadView>(
    `/admin/support/threads/${id}/attachments/upload-url`,
    { contentType: file.type, sizeBytes: file.size },
  )
  return data
}
