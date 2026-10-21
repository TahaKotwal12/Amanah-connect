import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render } from '@testing-library/react'
import { createMemoryRouter, type InitialEntry } from 'react-router'
import { RouterProvider } from 'react-router/dom'
import { appRoutes } from '@/routes'

/** Render the real route table (layouts, guards, lazy pages) starting at `entry`. */
export function renderApp(entry: InitialEntry, options: { client?: QueryClient } = {}) {
  const router = createMemoryRouter(appRoutes, { initialEntries: [entry] })
  const client =
    options.client ?? new QueryClient({ defaultOptions: { queries: { retry: false } } })
  const view = render(
    <QueryClientProvider client={client}>
      <RouterProvider router={router} />
    </QueryClientProvider>,
  )
  return { router, client, ...view }
}

/** The router's "where they came from" state, as ProtectedRoute leaves it. */
export function cameFrom(pathname: string, search = '') {
  return { pathname: '/login', state: { from: { pathname, search, hash: '' } } }
}
