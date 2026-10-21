import { keepPreviousData, useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import type {
  CreatePlatformAnnouncementRequest,
  UpdatePlatformAnnouncementRequest,
  Uuid,
} from '@/types'
import {
  createAnnouncement,
  fetchAnnouncement,
  fetchAnnouncements,
  previewAnnouncement,
  scheduleAnnouncement,
  sendAnnouncement,
  sendAnnouncementTest,
  updateAnnouncement,
} from '../api/announcements'
import { superadminKeys, type AnnouncementListParams } from '../queryKeys'

export function useAnnouncements(params: AnnouncementListParams) {
  return useQuery({
    queryKey: superadminKeys.announcements.list(params),
    queryFn: () => fetchAnnouncements(params),
    placeholderData: keepPreviousData,
  })
}

export function useAnnouncement(id: Uuid | null) {
  return useQuery({
    queryKey: superadminKeys.announcements.detail(id ?? ''),
    queryFn: () => fetchAnnouncement(id ?? ''),
    enabled: id !== null,
  })
}

/** The preview counts what would be reached right now, so it is fetched fresh each time. */
export function useAnnouncementPreview(id: Uuid | null) {
  return useQuery({
    queryKey: superadminKeys.announcements.preview(id ?? ''),
    queryFn: () => previewAnnouncement(id ?? ''),
    enabled: id !== null,
    gcTime: 0,
  })
}

function useRefreshAnnouncements() {
  const client = useQueryClient()
  return () => client.invalidateQueries({ queryKey: superadminKeys.announcements.all })
}

export function useCreateAnnouncement() {
  const refresh = useRefreshAnnouncements()
  return useMutation({
    mutationFn: (body: CreatePlatformAnnouncementRequest) => createAnnouncement(body),
    onSuccess: refresh,
  })
}

export function useUpdateAnnouncement() {
  const refresh = useRefreshAnnouncements()
  return useMutation({
    mutationFn: ({ id, body }: { id: Uuid; body: UpdatePlatformAnnouncementRequest }) =>
      updateAnnouncement(id, body),
    onSuccess: refresh,
  })
}

export function useScheduleAnnouncement() {
  const refresh = useRefreshAnnouncements()
  return useMutation({
    mutationFn: ({ id, scheduledAt }: { id: Uuid; scheduledAt: string }) =>
      scheduleAnnouncement(id, scheduledAt),
    onSuccess: refresh,
  })
}

/** Not optimistic and not retried: sending emails every admin, once. */
export function useSendAnnouncement() {
  const refresh = useRefreshAnnouncements()
  return useMutation({
    mutationFn: (id: Uuid) => sendAnnouncement(id),
    retry: false,
    onSuccess: refresh,
  })
}

export function useSendAnnouncementTest() {
  return useMutation({ mutationFn: (id: Uuid) => sendAnnouncementTest(id), retry: false })
}
