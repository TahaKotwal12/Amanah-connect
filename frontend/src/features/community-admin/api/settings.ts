import { api } from '@/lib/axios'
import type {
  AnonymiseResult,
  DownloadUrl,
  ExportView,
  LogoUploadView,
  SettingsView,
  UpdateSettingsRequest,
  Uuid,
} from '@/types'

export async function fetchSettings(): Promise<SettingsView> {
  const { data } = await api.get<SettingsView>('/community/settings')
  return data
}

/** Partial: a field left out is unchanged; an empty string clears an optional text field. */
export async function updateSettings(body: UpdateSettingsRequest): Promise<SettingsView> {
  const { data } = await api.patch<SettingsView>('/community/settings', body)
  return data
}

/** Step one of a logo change: ask where to put the file. Then upload it, then PATCH `logoKey`. */
export async function requestLogoUpload(file: File): Promise<LogoUploadView> {
  const { data } = await api.post<LogoUploadView>('/community/settings/logo/upload-url', {
    contentType: file.type,
    sizeBytes: file.size,
  })
  return data
}

export async function removeLogo(): Promise<SettingsView> {
  const { data } = await api.delete<SettingsView>('/community/settings/logo')
  return data
}

// ---- security ---------------------------------------------------------------------------------

export async function changePassword(body: {
  currentPassword: string
  newPassword: string
}): Promise<void> {
  await api.post('/auth/password/change', body)
}

/** Needs the password and one authenticator or recovery code. */
export async function disableTwoFactor(body: {
  password: string
  code?: string
  recoveryCode?: string
}): Promise<void> {
  await api.post('/auth/2fa/disable', body)
}

// ---- data -------------------------------------------------------------------------------------

export async function fetchDataExports(): Promise<ExportView[]> {
  const { data } = await api.get<ExportView[]>('/community/data-exports')
  return data
}

/** Answers 202 at once; a background job builds the ZIP and emails a link. */
export async function requestDataExport(): Promise<ExportView> {
  const { data } = await api.post<ExportView>('/community/data-exports')
  return data
}

export async function fetchExportDownloadUrl(id: Uuid): Promise<DownloadUrl> {
  const { data } = await api.get<DownloadUrl>(`/community/data-exports/${id}/download-url`)
  return data
}

/** Irreversible. The member's number is typed back to confirm. */
export async function anonymiseMember(
  id: Uuid,
  body: { memberNo: string; reason: string },
): Promise<AnonymiseResult> {
  const { data } = await api.post<AnonymiseResult>(`/community/members/${id}/anonymise`, body)
  return data
}
