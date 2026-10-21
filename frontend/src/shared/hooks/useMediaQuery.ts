import { useSyncExternalStore } from 'react'

/**
 * Whether a CSS media query currently matches, live. `fallback` is used where matchMedia does not
 * exist (tests, server rendering): pass the answer for the layout you want by default.
 */
export function useMediaQuery(query: string, fallback = true): boolean {
  return useSyncExternalStore(
    (notify) => {
      if (typeof window.matchMedia !== 'function') return () => undefined
      const list = window.matchMedia(query)
      list.addEventListener('change', notify)
      return () => {
        list.removeEventListener('change', notify)
      }
    },
    () => (typeof window.matchMedia === 'function' ? window.matchMedia(query).matches : fallback),
    () => fallback,
  )
}
