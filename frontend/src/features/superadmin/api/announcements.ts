import { api } from '@/lib/axios'
import type {
  CreatePlatformAnnouncementRequest,
  PageResponse,
  PlatformAnnouncementView,
  PlatformPreviewView,
  TestSendView,
  UpdatePlatformAnnouncementRequest,
  Uuid,
} from '@/types'
import type { AnnouncementListParams } from '../queryKeys'
import { toQuery } from './params'

export async function fetchAnnouncements(
  params: AnnouncementListParams,
): Promise<PageResponse<PlatformAnnouncementView>> {
  const { data } = await api.get<PageResponse<PlatformAnnouncementView>>('/admin/announcements', {
    params: toQuery(params),
  })
  return data
}

export async function fetchAnnouncement(id: Uuid): Promise<PlatformAnnouncementView> {
  const { data } = await api.get<PlatformAnnouncementView>(`/admin/announcements/${id}`)
  return data
}

export async function createAnnouncement(
  body: CreatePlatformAnnouncementRequest,
): Promise<PlatformAnnouncementView> {
  const { data } = await api.post<PlatformAnnouncementView>('/admin/announcements', body)
  return data
}

export async function updateAnnouncement(
  id: Uuid,
  body: UpdatePlatformAnnouncementRequest,
): Promise<PlatformAnnouncementView> {
  const { data } = await api.patch<PlatformAnnouncementView>(`/admin/announcements/${id}`, body)
  return data
}

export async function scheduleAnnouncement(
  id: Uuid,
  scheduledAt: string,
): Promise<PlatformAnnouncementView> {
  const { data } = await api.post<PlatformAnnouncementView>(`/admin/announcements/${id}/schedule`, {
    scheduledAt,
  })
  return data
}

/** Irreversible: a second call is refused, never a second round of emails. */
export async function sendAnnouncement(id: Uuid): Promise<PlatformAnnouncementView> {
  const { data } = await api.post<PlatformAnnouncementView>(`/admin/announcements/${id}/send`)
  return data
}

export async function previewAnnouncement(id: Uuid): Promise<PlatformPreviewView> {
  const { data } = await api.get<PlatformPreviewView>(`/admin/announcements/${id}/preview`)
  return data
}

export async function sendAnnouncementTest(id: Uuid): Promise<TestSendView> {
  const { data } = await api.post<TestSendView>(`/admin/announcements/${id}/send-test`)
  return data
}
