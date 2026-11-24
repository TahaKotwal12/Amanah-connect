import { useRef } from 'react'

interface FocusHandlers {
  onOpenAutoFocus: (event: Event) => void
  onCloseAutoFocus: (event: Event) => void
}

/**
 * Give focus back to whatever had it when a modal opened.
 *
 * Radix returns focus on close only to a <Trigger> it renders itself. Most dialogs and sheets here
 * are opened from state (an "Add member" button sets `open`), so Radix has no trigger and focus
 * would fall to the top of the page, which loses a keyboard user's place. Spread the result onto
 * the Radix Content. The opener is read when the modal opens (focus is still on it then), not when
 * the component first renders: the content component renders while the modal is still closed.
 */
export function useReturnFocus(
  handlers: {
    onOpenAutoFocus?: ((event: Event) => void) | undefined
    onCloseAutoFocus?: ((event: Event) => void) | undefined
  } = {},
): FocusHandlers {
  const opener = useRef<HTMLElement | null>(null)
  return {
    onOpenAutoFocus: (event) => {
      opener.current = document.activeElement instanceof HTMLElement ? document.activeElement : null
      handlers.onOpenAutoFocus?.(event)
    },
    onCloseAutoFocus: (event) => {
      handlers.onCloseAutoFocus?.(event)
      const target = opener.current
      opener.current = null
      if (event.defaultPrevented) return
      if (target && target !== document.body && target.isConnected) {
        event.preventDefault()
        target.focus()
      }
    },
  }
}
