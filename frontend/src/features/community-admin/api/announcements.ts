import { api } from '@/lib/axios'
import { toQuery } from '@/lib/query'
import type {
  AnnouncementPreviewView,
  AnnouncementStatus,
  AnnouncementView,
  CreateAnnouncementRequest,
  PageResponse,
  ScheduleRequest,
  TestSendView,
  UpdateAnnouncementRequest,
  Uuid,
} from '@/types'
import type { AnnouncementListParams } from '../queryKeys'

export async function fetchAnnouncements(
  params: AnnouncementListParams,
): Promise<PageResponse<AnnouncementView>> {
  const { data } = await api.get<PageResponse<AnnouncementView>>('/community/announcements', {
    params: toQuery(params),
  })
  return data
}

export async function fetchAnnouncement(id: Uuid): Promise<AnnouncementView> {
  const { data } = await api.get<AnnouncementView>(`/community/announcements/${id}`)
  return data
}

export async function createAnnouncement(
  body: CreateAnnouncementRequest,
): Promise<AnnouncementView> {
  const { data } = await api.post<AnnouncementView>('/community/announcements', body)
  return data
}

export async function updateAnnouncement(
  id: Uuid,
  body: UpdateAnnouncementRequest,
): Promise<AnnouncementView> {
  const { data } = await api.patch<AnnouncementView>(`/community/announcements/${id}`, body)
  return data
}

export async function scheduleAnnouncement(
  id: Uuid,
  body: ScheduleRequest,
): Promise<AnnouncementView> {
  const { data } = await api.post<AnnouncementView>(`/community/announcements/${id}/schedule`, body)
  return data
}

export async function unscheduleAnnouncement(id: Uuid): Promise<AnnouncementView> {
  const { data } = await api.post<AnnouncementView>(`/community/announcements/${id}/unschedule`)
  return data
}

export async function sendAnnouncement(id: Uuid): Promise<AnnouncementView> {
  const { data } = await api.post<AnnouncementView>(`/community/announcements/${id}/send`)
  return data
}

export async function previewAnnouncement(id: Uuid): Promise<AnnouncementPreviewView> {
  const { data } = await api.get<AnnouncementPreviewView>(`/community/announcements/${id}/preview`)
  return data
}

export async function sendAnnouncementTest(id: Uuid): Promise<TestSendView> {
  const { data } = await api.post<TestSendView>(`/community/announcements/${id}/send-test`)
  return data
}

export type { AnnouncementStatus }
