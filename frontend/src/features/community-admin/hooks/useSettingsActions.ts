import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import type { UpdateSettingsRequest, Uuid } from '@/types'
import {
  anonymiseMember,
  changePassword,
  disableTwoFactor,
  fetchDataExports,
  fetchExportDownloadUrl,
  removeLogo,
  requestDataExport,
  updateSettings,
} from '../api/settings'
import { communityAdminKeys } from '../queryKeys'

export function useUpdateSettings() {
  const client = useQueryClient()
  return useMutation({
    mutationFn: (body: UpdateSettingsRequest) => updateSettings(body),
    onSuccess: (settings) => {
      client.setQueryData(communityAdminKeys.settings(), settings)
      // The shell shows the community name and logo.
      void client.invalidateQueries({ queryKey: communityAdminKeys.plan() })
    },
  })
}

export function useRemoveLogo() {
  const client = useQueryClient()
  return useMutation({
    mutationFn: removeLogo,
    onSuccess: (settings) => client.setQueryData(communityAdminKeys.settings(), settings),
  })
}

export function useChangePassword() {
  return useMutation({ mutationFn: changePassword })
}

export function useDisableTwoFactor() {
  return useMutation({ mutationFn: disableTwoFactor })
}

/** Polls while an export is being prepared, then stops. */
export function useDataExports() {
  return useQuery({
    queryKey: communityAdminKeys.dataExports(),
    queryFn: fetchDataExports,
    refetchInterval: (query) =>
      query.state.data?.some((e) => e.status === 'PENDING' || e.status === 'RUNNING')
        ? 5_000
        : false,
    meta: { silent: true },
  })
}

export function useRequestDataExport() {
  const client = useQueryClient()
  return useMutation({
    mutationFn: requestDataExport,
    onSuccess: () => client.invalidateQueries({ queryKey: communityAdminKeys.dataExports() }),
  })
}

export function useExportDownloadUrl() {
  return useMutation({ mutationFn: (id: Uuid) => fetchExportDownloadUrl(id) })
}

/** Erasing a member changes the member lists and everything that shows a name. */
export function useAnonymiseMember() {
  const client = useQueryClient()
  return useMutation({
    mutationFn: ({ id, memberNo, reason }: { id: Uuid; memberNo: string; reason: string }) =>
      anonymiseMember(id, { memberNo, reason }),
    onSuccess: () =>
      Promise.all([
        client.invalidateQueries({ queryKey: communityAdminKeys.members.all }),
        client.invalidateQueries({ queryKey: communityAdminKeys.invoices.all }),
        client.invalidateQueries({ queryKey: communityAdminKeys.complaints.all }),
      ]),
  })
}
