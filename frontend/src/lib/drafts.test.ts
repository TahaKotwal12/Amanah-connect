import { authStore } from '@/store/authStore'
import { clearAllDrafts, clearDraft, loadDraft, saveDraft } from './drafts'
import type { UserSummary } from '@/types'

const user = (id: string): UserSummary => ({
  id,
  email: `${id}@example.test`,
  fullName: id,
  role: 'COMMUNITY_ADMIN',
  status: 'ACTIVE',
})

function signIn(id: string) {
  authStore.setState({ user: user(id), accessToken: 't', sessionExpired: false })
}

afterEach(() => {
  clearAllDrafts()
  authStore.getState().clearSession()
})

describe('drafts', () => {
  it('keeps and clears a draft by key', () => {
    saveDraft('a', { title: 'Hello' })
    expect(loadDraft('a')).toEqual({ title: 'Hello' })
    clearDraft('a')
    expect(loadDraft('a')).toBeUndefined()
  })

  it('survives an expired session, so the person finds their text after signing back in', () => {
    signIn('u1')
    saveDraft('complaint', { subject: 'Leak' })
    authStore.getState().clearSession({ expired: true })
    expect(loadDraft('complaint')).toEqual({ subject: 'Leak' })
    signIn('u1')
    expect(loadDraft('complaint')).toEqual({ subject: 'Leak' })
  })

  it('is thrown away when the person signs out on purpose', () => {
    signIn('u1')
    saveDraft('complaint', { subject: 'Leak' })
    authStore.getState().clearSession()
    expect(loadDraft('complaint')).toBeUndefined()
  })

  it('is never shown to a different account that signs in next', () => {
    signIn('u1')
    saveDraft('complaint', { subject: 'Leak' })
    authStore.getState().clearSession({ expired: true })
    signIn('u2')
    expect(loadDraft('complaint')).toBeUndefined()
  })
})
