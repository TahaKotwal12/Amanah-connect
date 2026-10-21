import { api } from '@/lib/axios'
import { toQuery } from '@/lib/query'
import type {
  CreateThreadRequest,
  MessagePage,
  MessageView,
  PageResponse,
  PostMessageRequest,
  SupportSummary,
  SupportUploadView,
  ThreadView,
  Uuid,
} from '@/types'
import type { SupportListParams } from '../queryKeys'

export async function fetchThreads(params: SupportListParams): Promise<PageResponse<ThreadView>> {
  const { data } = await api.get<PageResponse<ThreadView>>('/community/support/threads', {
    params: toQuery(params),
  })
  return data
}

export async function fetchThread(id: Uuid): Promise<ThreadView> {
  const { data } = await api.get<ThreadView>(`/community/support/threads/${id}`)
  return data
}

export async function createThread(body: CreateThreadRequest): Promise<ThreadView> {
  const { data } = await api.post<ThreadView>('/community/support/threads', body)
  return data
}

/** The cheap thing to poll: unread numbers and where each thread is up to. */
export async function fetchSummary(): Promise<SupportSummary> {
  const { data } = await api.get<SupportSummary>('/community/support/summary')
  return data
}

/** Messages after a cursor, oldest first. Reading them does not mark anything read. */
export async function fetchMessages(id: Uuid, after: number): Promise<MessagePage> {
  const { data } = await api.get<MessagePage>(`/community/support/threads/${id}/messages`, {
    params: { after, limit: 100 },
  })
  return data
}

export async function postMessage(id: Uuid, body: PostMessageRequest): Promise<MessageView> {
  const { data } = await api.post<MessageView>(`/community/support/threads/${id}/messages`, body)
  return data
}

export async function markThreadRead(id: Uuid, upToSeq?: number): Promise<SupportSummary> {
  const { data } = await api.post<SupportSummary>(
    `/community/support/threads/${id}/read`,
    upToSeq === undefined ? {} : { upToSeq },
  )
  return data
}

/** One upload route for a new conversation and for replies. */
export async function requestAttachmentUpload(file: {
  type: string
  size: number
}): Promise<SupportUploadView> {
  const { data } = await api.post<SupportUploadView>('/community/support/attachments/upload-url', {
    contentType: file.type,
    sizeBytes: file.size,
  })
  return data
}
