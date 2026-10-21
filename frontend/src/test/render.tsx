import { render, type RenderOptions } from '@testing-library/react'
import type { ReactElement } from 'react'
import { MemoryRouter } from 'react-router'

/** Render inside a router, for components that contain links. */
export function renderWithRouter(ui: ReactElement, options?: RenderOptions & { route?: string }) {
  const { route = '/', ...rest } = options ?? {}
  return render(<MemoryRouter initialEntries={[route]}>{ui}</MemoryRouter>, rest)
}

/** Make `useMediaQuery('(min-width: 768px)')` answer for a phone (false) or desktop (true). */
export function mockViewport(isDesktop: boolean): void {
  window.matchMedia = (query: string) => ({
    matches: isDesktop,
    media: query,
    onchange: null,
    addEventListener: () => undefined,
    removeEventListener: () => undefined,
    addListener: () => undefined,
    removeListener: () => undefined,
    dispatchEvent: () => false,
  })
}

export function clearViewportMock(): void {
  // jsdom has no matchMedia: deleting restores the "desktop by default" fallback.
  Reflect.deleteProperty(window, 'matchMedia')
}
