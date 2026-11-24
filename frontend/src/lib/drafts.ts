import { useCallback, useEffect } from 'react'
import type { FieldValues, UseFormReturn } from 'react-hook-form'
import { authStore } from '@/store/authStore'

/**
 * Unsaved form drafts, kept in memory only (never localStorage: a draft can hold member details).
 *
 * Why: when a session expires the router moves to /login with a SPA navigation, which unmounts the
 * open form. Without this the person signs in again and finds the half-written complaint gone.
 * Drafts survive that, and nothing else:
 *   - a successful save discards the draft (the form calls `discard`),
 *   - closing a form on purpose discards it (the form calls `discard` from its close handler),
 *   - signing out, or a different person signing in, discards everything,
 *   - a page reload loses them all (they are in memory).
 */
const drafts = new Map<string, unknown>()

/** Whose drafts these are. A draft is never shown to a different account. */
let owner: string | null = null

authStore.subscribe((state, previous) => {
  const id = state.user?.id ?? null
  const was = previous.user?.id ?? null
  if (id !== null) {
    if (owner !== null && owner !== id) drafts.clear()
    owner = id
    return
  }
  // No user: an expired session keeps its drafts (the person is about to sign back in); a sign-out
  // the person asked for does not.
  if (was !== null && !state.sessionExpired) {
    drafts.clear()
    owner = null
  }
})

export function loadDraft<T>(key: string): T | undefined {
  return drafts.get(key) as T | undefined
}

export function saveDraft(key: string, value: unknown): void {
  drafts.set(key, value)
}

export function clearDraft(key: string): void {
  drafts.delete(key)
}

export function clearAllDrafts(): void {
  drafts.clear()
  owner = null
}

/**
 * Keep a React Hook Form form's values as a draft while `active`, and give back the draft (if
 * any) to start from. Use `draft ?? defaults` where the form resets on open, and call `discard`
 * when the form is saved or closed on purpose.
 *
 *   const { initial, discard } = useFormDraft(form, 'complaint:new', open)
 *   useEffect(() => { if (open) form.reset(initial(emptyComplaint())) }, [open])
 */
export function useFormDraft<T extends FieldValues>(
  form: UseFormReturn<T>,
  key: string,
  active = true,
): { initial: (defaults: T) => T; discard: () => void } {
  useEffect(() => {
    if (!active) return
    const subscription = form.watch((values) => {
      saveDraft(key, values)
    })
    return () => {
      subscription.unsubscribe()
    }
  }, [form, key, active])

  const initial = useCallback(
    (defaults: T): T => {
      const saved = loadDraft<Partial<T>>(key)
      return saved ? { ...defaults, ...saved } : defaults
    },
    [key],
  )

  const discard = useCallback(() => {
    clearDraft(key)
  }, [key])

  return { initial, discard }
}
